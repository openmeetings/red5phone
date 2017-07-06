package org.red5.sip.net.rtp;

import static org.red5.sip.util.BytesBuffer.READY;

import java.lang.ref.WeakReference;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Iterator;

import org.apache.mina.util.ConcurrentHashSet;
import org.red5.codecs.SIPCodec;
import org.red5.codecs.asao.ByteStream;
import org.red5.codecs.asao.Decoder;
import org.red5.sip.app.IMediaReceiver;
import org.red5.sip.app.IMediaSender;
import org.red5.sip.app.IMediaStream;
import org.red5.sip.util.ResampleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.laszlosystems.libresample4j.Resampler;

import local.net.RtpPacket;
import local.net.RtpSocket;

public class RTPStreamMultiplexingSender implements IMediaSender, Runnable {
	protected static Logger log = LoggerFactory.getLogger(RTPStreamMultiplexingSender.class);

	public static int RTP_HEADER_SIZE = 12;

	public static enum SAMPLE_RATE {
		SAMPLING_8000(8000, 8), SAMPLING_16000(16000, 16), SAMPLING_32000(32000, 32), SAMPLING_48000(48000, 48), SAMPLING_11025(
				11025, 11), SAMPLING_22050(22050, 22), SAMPLING_44100(44100, 44);

		public int rate;
		public int shortName;

		SAMPLE_RATE(int rate, int shortName) {
			this.rate = rate;
			this.shortName = shortName;
		}

		public static SAMPLE_RATE findByShortName(int shortName) {
			for (SAMPLE_RATE s : SAMPLE_RATE.values()) {
				if (s.shortName == shortName) {
					return s;
				}
			}
			throw new IllegalArgumentException("Invalid sample rate");
		}
	}

	public static SAMPLE_RATE sampling = SAMPLE_RATE.SAMPLING_22050;
	protected static final int NELLYMOSER_DECODED_PACKET_SIZE = 256;// *
	protected static final int NELLYMOSER_ENCODED_PACKET_SIZE = 64;// *
																	// sampling.blocks;

	RtpSocket rtpSocket = null;

	/**
	 * Sip codec to be used on audio session
	 */
	protected SIPCodec sipCodec = null;

	boolean socketIsLocal = false;

	boolean doSync = true;

	private Decoder decoder;

	private byte[] packetBuffer;

	private RtpPacket rtpPacket;

	private int seqn = 0;

	private long time = 0;

	// Temporary buffer with received PCM audio from FlashPlayer.
	private float[] tempBuffer;
	private float[] multiplexedBuffer;

	private Resampler resampler;
	private double factor = 1;

	private Thread sendThread = new Thread(this, "RTPStreamMultiplexingSender sendThread");

	ConcurrentHashSet<WeakReference<RTPStreamForMultiplex>> streamSet = new ConcurrentHashSet<>();
	// Set<RTPStreamForMultiplex> streamSet = Collections.synchronizedSet(new
	// WeakHashSet<RTPStreamForMultiplex>());

	// Floats remaining on temporary buffer.
	int tempBufferRemaining = 0;

	// Encoding buffer used to encode to final codec format;
	float[] encodingBuffer;

	// Offset of encoding buffer.
	int encodingOffset = 0;

	// Indicates whether the current asao buffer was processed.
	boolean asao_buffer_processed = false;

	// Indicates whether the handling buffers have already
	// been initialized.
	boolean hasInitilializedBuffers = false;

	/**
	 * Constructs a RtpStreamSender.
	 *
	 * @param mediaReceiver
	 *            the RTMP stream source
	 * @param do_sync
	 *            whether time synchronization must be performed by the RtpStreamSender, or it is performed by the
	 *            InputStream (e.g. the system audio input)
	 * @param sipCodec
	 *            codec to be used on audio session
	 * @param dest_addr
	 *            the destination address
	 * @param dest_port
	 *            the destination port
	 */

	public RTPStreamMultiplexingSender(IMediaReceiver mediaReceiver, boolean do_sync, SIPCodec sipCodec,
			String dest_addr, int dest_port) {

		init(mediaReceiver, do_sync, sipCodec, null, dest_addr, dest_port);
	}

	/**
	 * Constructs a RtpStreamSender.
	 *
	 * @param mediaReceiver
	 *            the RTMP stream source
	 * @param do_sync
	 *            whether time synchronization must be performed by the RtpStreamSender, or it is performed by the
	 *            InputStream (e.g. the system audio input)
	 * @param sipCodec
	 *            codec to be used on audio session
	 * @param src_socket
	 *            the socket used to send the RTP packet
	 * @param dest_addr
	 *            the destination address
	 * @param dest_port
	 *            the thestination port
	 */
	public RTPStreamMultiplexingSender(IMediaReceiver mediaReceiver, boolean do_sync, SIPCodec sipCodec,
			DatagramSocket src_socket, String dest_addr, int dest_port) {

		init(mediaReceiver, do_sync, sipCodec, src_socket, dest_addr, dest_port);
	}

	/**
	 * Inits the RtpStreamSender
	 */
	private void init(IMediaReceiver mediaReceiver, boolean do_sync, SIPCodec sipCodec, DatagramSocket src_socket,
			String dest_addr, int dest_port) {

		mediaReceiver.setAudioSender(this);
		this.sipCodec = sipCodec;
		this.doSync = do_sync;

		try {
			if (src_socket == null) {

				src_socket = new DatagramSocket();
				socketIsLocal = true;
			}

			rtpSocket = new RtpSocket(src_socket, InetAddress.getByName(dest_addr), dest_port);
		} catch (Exception e) {
			log.error("Exception", e);
		}
	}

	@Override
	public void start() {
		packetBuffer = new byte[sipCodec.getOutgoingEncodedFrameSize() + RTP_HEADER_SIZE];
		rtpPacket = new RtpPacket(packetBuffer, 0);
		rtpPacket.setPayloadType(sipCodec.getCodecId());

		seqn = 0;
		time = 0;

		println("start()", "using blocks of " + (packetBuffer.length - RTP_HEADER_SIZE) + " bytes.");

		decoder = new Decoder();

		sendThread.start();
	}

	@Override
	public IMediaStream createStream(Number streamId) {
		RTPStreamForMultiplex stream = new RTPStreamForMultiplex(streamId);
		streamSet.add(new WeakReference<>(stream));
		return stream;
	}

	@Override
	public void deleteStream(Number streamId) {
		for (Iterator<WeakReference<RTPStreamForMultiplex>> iterator = streamSet.iterator(); iterator.hasNext();) {
			WeakReference<RTPStreamForMultiplex> ref = iterator.next();
			RTPStreamForMultiplex stream = ref.get();
			try {
				if (stream != null && stream.getStreamId() == streamId) {
					iterator.remove();
				}
			} catch (NullPointerException ignored) {

			}
		}
	}

	private void doRtpDelay(float bufferUsage) {
		// TODO: make proper pause correction.
		// Pause should not exceed packetization and prevent emtying of buffer
		try {
			long pause = sipCodec.getOutgoingPacketization() - 1;
			if (bufferUsage > .5f) {
				pause -= 5;
			}
			if (bufferUsage > READY) {
				pause -= 1;
			}
			log.trace("Sleep pause: " + pause);
			Thread.sleep(pause, 800000);
		} catch (Exception e) {
		}
	}

	@Override
	public void run() {
		if (!hasInitilializedBuffers) {
			multiplexedBuffer = new float[NELLYMOSER_DECODED_PACKET_SIZE];
			encodingBuffer = new float[sipCodec.getOutgoingDecodedFrameSize()];

			if (sipCodec.getSampleRate() == sampling.rate) {
				tempBuffer = new float[NELLYMOSER_DECODED_PACKET_SIZE];
				resampler = null;
			} else {
				factor = sipCodec.getSampleRate() / (double) sampling.rate;
				resampler = new Resampler(true, factor, factor);
				tempBuffer = new float[(int) (NELLYMOSER_DECODED_PACKET_SIZE * factor)];
			}
			hasInitilializedBuffers = true;
		}

		float[] decodedBuffer = new float[NELLYMOSER_DECODED_PACKET_SIZE];
		byte[] asaoBuffer = new byte[NELLYMOSER_ENCODED_PACKET_SIZE];

		Number disableStream = 0;

		while (rtpSocket != null) {
			float bufferUsage = 0;
			int multiplexingCount = 0;
			try {
				for (Iterator<WeakReference<RTPStreamForMultiplex>> i = streamSet.iterator(); i.hasNext();) {
					int len = -1;
					WeakReference<RTPStreamForMultiplex> ref = i.next();
					RTPStreamForMultiplex stream = ref.get();
					if (stream != null) {
						if (stream.ready() && !stream.getStreamId().equals(disableStream)) {
							len = stream.read(asaoBuffer, 0);
							bufferUsage = Math.max(bufferUsage, stream.bufferUsage());
							log.trace("Stream id {}, buffer {}", stream.getStreamId(), stream.bufferUsage());
						} else {
							continue;
						}
					} else {
						i.remove();
						continue;
					}
					if (len != -1) {
						ByteStream audioStream = new ByteStream(asaoBuffer, 1, NELLYMOSER_ENCODED_PACKET_SIZE);
						stream.decoderMap = decoder.decode(stream.decoderMap, audioStream.bytes, 0, decodedBuffer, 0);
						// fillDecodedBuffer(asaoBuffer, decodedBuffer);
						if (multiplexingCount > 0) {
							ResampleUtils.multiplex(multiplexedBuffer, decodedBuffer);
						} else {
							System.arraycopy(decodedBuffer, 0, multiplexedBuffer, 0, decodedBuffer.length);
						}
						multiplexingCount++;
					}
					Thread.yield();
				}
			} catch (Exception e) {
				log.error("Exception", e);
			}
			if (multiplexingCount > 0) {
				log.trace("Send: multiplexed: " + multiplexingCount + ", total streams: " + streamSet.size());
				// ResampleUtils.normalize(multiplexedBuffer,
				// 1.0f/multiplexingCount);
				ResampleUtils.normalize(multiplexedBuffer, multiplexedBuffer.length);
				try {
					asao_buffer_processed = false;
					do {
						int encodedBytes = fillRtpPacketBuffer();
						if (encodedBytes == 0) {
							break;
						}
						if (encodingOffset == sipCodec.getOutgoingDecodedFrameSize()) {
							rtpSocketSend(rtpPacket);
							doRtpDelay(bufferUsage);
							encodingOffset = 0;
						}
					} while (!asao_buffer_processed);
				} catch (Exception e) {
					log.error("Error preparing RTP packet", e);
				}
			}

		}
	}

	/**
	 * Fill the buffer of RtpPacket with necessary data.
	 */
	private int fillRtpPacketBuffer() {

		int copyingSize = 0;
		int finalCopySize = 0;
		byte[] codedBuffer = new byte[sipCodec.getOutgoingEncodedFrameSize()];

		try {

			if ((tempBufferRemaining + encodingOffset) >= sipCodec.getOutgoingDecodedFrameSize()) {

				copyingSize = encodingBuffer.length - encodingOffset;

				System.arraycopy(tempBuffer, tempBuffer.length - tempBufferRemaining, encodingBuffer, encodingOffset, copyingSize);

				encodingOffset = sipCodec.getOutgoingDecodedFrameSize();
				tempBufferRemaining -= copyingSize;
				finalCopySize = sipCodec.getOutgoingDecodedFrameSize();
			} else {
				if (tempBufferRemaining > 0) {
					System.arraycopy(tempBuffer, tempBuffer.length - tempBufferRemaining
							, encodingBuffer, encodingOffset, tempBufferRemaining);

					encodingOffset += tempBufferRemaining;
					finalCopySize += tempBufferRemaining;
					tempBufferRemaining = 0;

				}

				// Process next buffer
				asao_buffer_processed = true;
				if (resampler == null) {
					System.arraycopy(multiplexedBuffer, 0, tempBuffer, 0, tempBuffer.length);
				} else {
					resampler.process(factor, multiplexedBuffer, 0, multiplexedBuffer.length, true, tempBuffer, 0,
							tempBuffer.length);
				}
				// tempBuffer = ResampleUtils.normalize(tempBuffer, 256); //
				// normalise volume

				tempBufferRemaining = tempBuffer.length;

				if (tempBuffer.length <= 0) {

					println("fillRtpPacketBuffer", "Asao decoder Error.");
				}

				// Try to complete the encodingBuffer with necessary data.

				if ((encodingOffset + tempBufferRemaining) > sipCodec.getOutgoingDecodedFrameSize()) {
					copyingSize = encodingBuffer.length - encodingOffset;
				} else {
					copyingSize = tempBufferRemaining;
				}

				System.arraycopy(tempBuffer, 0, encodingBuffer, encodingOffset, copyingSize);

				encodingOffset += copyingSize;
				tempBufferRemaining -= copyingSize;
				finalCopySize += copyingSize;
			}

			if (encodingOffset == encodingBuffer.length) {
				int encodedBytes = sipCodec.pcmToCodec(encodingBuffer, codedBuffer);
				if (encodedBytes == sipCodec.getOutgoingEncodedFrameSize()) {
					System.arraycopy(codedBuffer, 0, packetBuffer, RTP_HEADER_SIZE, codedBuffer.length);
				}
			}

		} catch (Exception e) {
			log.error("Exception", e);
		}

		return finalCopySize;
	}

	@Override
	public void halt() {

		DatagramSocket socket = rtpSocket.getDatagramSocket();

		rtpSocket.close();

		if (socketIsLocal && socket != null) {
			socket.close();
		}

		rtpSocket = null;
		sendThread = null;

		println("halt", "Terminated");
	}

	private void rtpSocketSend(RtpPacket rtpPacket) {
		try {
			rtpPacket.setTimestamp(time);
			rtpPacket.setSequenceNumber(seqn++);
			rtpPacket.setPayloadLength(sipCodec.getOutgoingEncodedFrameSize());
			rtpPacket.setPayloadType(sipCodec.getCodecId());
			rtpSocket.send(rtpPacket);
			time += sipCodec.getOutgoingPacketization();
		} catch (Exception e) {
			log.error("Error sending RTP packet", e);
		}
	}

	private static void println(String method, String message) {
		log.debug("RTPStreamSender - " + method + " -> " + message);
	}
}
