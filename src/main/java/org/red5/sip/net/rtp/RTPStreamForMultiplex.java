package org.red5.sip.net.rtp;

import static org.red5.sip.net.rtp.RTPStreamMultiplexingSender.NELLYMOSER_ENCODED_PACKET_SIZE;
import static org.red5.sip.util.BytesBuffer.READY;

import org.red5.codecs.asao.DecoderMap;
import org.red5.sip.app.IMediaStream;
import org.red5.sip.util.BytesBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RTPStreamForMultiplex implements IMediaStream {
	protected static Logger log = LoggerFactory.getLogger(RTPStreamForMultiplex.class);
	private Number streamId;
	private boolean ready = false;
	protected DecoderMap decoderMap = null;
	private BytesBuffer buffer = new BytesBuffer(NELLYMOSER_ENCODED_PACKET_SIZE, 200) {
		@Override
		protected void onBufferOverflow() {
			super.onBufferOverflow();
			log.error("Stream {} buffer overflow. Buffer was cleared", streamId);
		}

		@Override
		protected void onBufferEmpty() {
			super.onBufferEmpty();
            /* Not ready only after buffer empty */
			ready = false;
			log.error("Stream {} buffer empty.", streamId);
		}
	};

	protected RTPStreamForMultiplex(Number streamId) {
		this.streamId = streamId;
	}

	public Number getStreamId() {
		return streamId;
	}

	public void send(long timestamp, byte[] asaoBuffer, int offset, int num) {
		log.trace("Stream {} send:: num: {} ready {}", streamId, num, ready);
		for (int i = 0; i < num; i += NELLYMOSER_ENCODED_PACKET_SIZE) {
			synchronized (this) {
				buffer.push(asaoBuffer, offset + i, NELLYMOSER_ENCODED_PACKET_SIZE);
			}
			Thread.yield();
		}
		synchronized (this) {
			if (!ready && buffer.bufferUsage() > READY) {
				ready = true;
			}
		}
	}

	protected synchronized boolean ready() {
		return ready;
	}

	protected synchronized float bufferUsage() {
		return buffer.bufferUsage();
	}

	protected synchronized int read(byte[] dst, int offset) {
		int read = buffer.take(dst, offset);
		log.trace("Stream {} read:: ready: {} read {}", streamId, ready, read);
		return read;
	}

	@Override
	public void stop() {
		// nothing to do
	}
}
