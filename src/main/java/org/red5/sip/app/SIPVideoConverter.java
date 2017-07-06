package org.red5.sip.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.red5.codecs.SIPCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.net.RtpPacket;

public class SIPVideoConverter {

	private static final Logger log = LoggerFactory.getLogger(SIPVideoConverter.class);
	private static final int MAX_RTP_PAYLOAD_SIZE = 1446;

	// rtp => rtmp
	private byte[] sps;
	private byte[] pps;
	private boolean sentSeq;
	private long lastFIRTime;
	private long startTs;
	private long startTm;
	private long startRelativeTime;
	private int lastReceivedSequenceNumber;
	private List<RtpPacketWrapper> packetsQueue;
	private SIPTransport sipTransport;
	private boolean fuaStartedAndNotFinished;

	// rtmp => rtp
	private int lenSize;
	private boolean spsSent;
	private boolean ppsSent;

	public SIPVideoConverter(SIPTransport sipTransport) {
		this.sipTransport = sipTransport;
		resetConverter();
		startRelativeTime = System.currentTimeMillis();
	}

	public void resetConverter() {
		log.debug("::resetConverter::");
		packetsQueue = new ArrayList<>();
		lastFIRTime = System.currentTimeMillis();
		sps = new byte[0];
		pps = new byte[0];
		sentSeq = false;
		startTs = -1;
		startTm = -1;
		lastReceivedSequenceNumber = -1;
		spsSent = false;
		ppsSent = false;
		fuaStartedAndNotFinished = false;
	}

	public List<RTMPPacketInfo> rtp2rtmp(RtpPacket packet, SIPCodec codec) {
		switch (codec.getCodecId()) {
			case 35:
				return rtp2rtmpH264(packet, codec);
			default:
				log.error("Unsuported codec type: " + codec.getCodecName());
				return new ArrayList<>();
		}
	}

	public List<RtpPacket> rtmp2rtp(byte data[], long ts, SIPCodec codec) {
		switch (codec.getCodecId()) {
			case 35:
				return rtmp2rtpH264(data, ts, codec);
			default:
				log.error("Unsuported codec type: " + codec.getCodecName());
				return new ArrayList<>();
		}
	}

	private List<RtpPacket> rtmp2rtpH264(byte data[], long ts, SIPCodec codec) {
		List<RtpPacket> result = new ArrayList<>();
		long ts1 = ts * codec.getSampleRate() / 1000;
		if (data[0] == 0x17 && data[1] == 0) {
			byte[] pdata = Arrays.copyOfRange(data, 2, data.length);
			int cfgVer = pdata[3];
			if (cfgVer == 1) {
				int lenSize = pdata[7] & 0x03 + 1;
				int numSPS = pdata[8] & 0x1f;
				pdata = Arrays.copyOfRange(pdata, 9, pdata.length);
				byte[] sps = null;
				for (int i = 0; i < numSPS; i++) {
					int lenSPS = (pdata[0] & 0xff) << 8 | pdata[1] & 0xff;
					pdata = Arrays.copyOfRange(pdata, 2, pdata.length);
					if (sps == null) {
						sps = Arrays.copyOf(pdata, lenSPS);
					}
					pdata = Arrays.copyOfRange(pdata, lenSPS, pdata.length);
				}
				int numPPS = pdata[0];
				pdata = Arrays.copyOfRange(pdata, 1, pdata.length);
				byte[] pps = null;
				for (int i = 0; i < numPPS; i++) {
					int lenPPS = (pdata[0] & 0xff) << 8 | pdata[1] & 0xff;
					pdata = Arrays.copyOfRange(pdata, 2, pdata.length);
					if (pps == null) {
						pps = Arrays.copyOf(pdata, lenPPS);
					}
					pdata = Arrays.copyOfRange(pdata, lenPPS, pdata.length);
				}
				this.lenSize = lenSize;
				if (sps != null) {
					spsSent = true;
					byte[] buffer = new byte[sps.length + 12];
					RtpPacket packet = new RtpPacket(buffer, 0);
					packet.setPayload(sps, sps.length);
					packet.setTimestamp(ts1);
					buffer[1] = (byte) 0xe3;
					result.add(packet);
				}
				if (pps != null) {
					ppsSent = true;
					byte[] buffer = new byte[pps.length + 12];
					RtpPacket packet = new RtpPacket(buffer, 0);
					packet.setPayload(pps, pps.length);
					packet.setTimestamp(ts1);
					buffer[1] = (byte) 0xe3;
					result.add(packet);
				}
			} else {
				log.debug("Unsuported cfgVer=" + cfgVer);
			}
		} else if ((data[0] == 0x17 || data[0] == 0x27) && data[1] == 1) {
			if (spsSent && ppsSent) {
				List<ByteArrayBuilder> nals = new ArrayList<>();
				byte[] pdata = Arrays.copyOfRange(data, 5, data.length);
				while (pdata.length > 0) {
					int nalSize = 0;
					switch (lenSize) {
					case 1:
						nalSize = pdata[lenSize - 1] & 0xff;
						break;
					case 2:
						nalSize = (pdata[lenSize - 2] & 0xff) << 8 | pdata[lenSize - 1] & 0xff;
						break;
					case 4:
						nalSize = (pdata[lenSize - 4] & 0xff) << 24 |
								  (pdata[lenSize - 3] & 0xff) << 16 |
								  (pdata[lenSize - 2] & 0xff) << 8  |
								  (pdata[lenSize - 1] & 0xff);
						break;
					default:
						throw new RuntimeException("Invalid length size: " + lenSize);
					}
					ByteArrayBuilder nalData = new ByteArrayBuilder(Arrays.copyOfRange(pdata, lenSize, lenSize + nalSize));
					nals.add(nalData);
					pdata = Arrays.copyOfRange(pdata, lenSize + nalSize, pdata.length);
				}
				if (nals.size() > 0) {
					byte[] remaining = nals.get(nals.size() - 1).buildArray();
					int nalType = remaining[0] & 0x1f;
					int nri = remaining[0] & 0x60;
					if (nalType == 5 || nalType == 1) {
						if (remaining.length < MAX_RTP_PAYLOAD_SIZE) {
							byte[] buffer = new byte[remaining.length + 12];
							RtpPacket packet = new RtpPacket(buffer, 0);
							packet.setPayload(remaining, remaining.length);
							packet.setTimestamp(ts1);
							buffer[1] = (byte) 0xe3; // marker and payload type
							result.add(packet);
						} else {
							byte start = (byte) 0x80;
							remaining = Arrays.copyOfRange(remaining, 1, remaining.length);
							while (remaining.length > 0) {
								pdata = Arrays.copyOf(remaining, Math.min(MAX_RTP_PAYLOAD_SIZE - 2, remaining.length));
								remaining = Arrays.copyOfRange(remaining, Math.min(MAX_RTP_PAYLOAD_SIZE - 2, remaining.length), remaining.length);
								byte end = (byte) ((remaining.length > 0)? 0: 0x40);
								ByteArrayBuilder payload = new ByteArrayBuilder((byte) (nri | 28), (byte) (start | end | nalType));
								payload.putArray(pdata);
								start = 0;

								byte[] buffer = new byte[payload.getLength() + 12];
								RtpPacket packet = new RtpPacket(buffer, 0);
								packet.setPayload(payload.buildArray(), payload.getLength());
								packet.setTimestamp(ts1);
								buffer[1] = (byte) ((end == 0x40)? 0xe3: 0x63);
								result.add(packet);
							}
						}
					}
				}
			}
		} else {
			log.debug("Missing rtmp data");
		}
		return result;
	}

	private List<RTMPPacketInfo> rtp2rtmpH264(RtpPacket packet, SIPCodec codec) {
		List<RTMPPacketInfo> result = new ArrayList<>();
		if (packet.getPayloadType() != 35) {
			return result;
		}
		if (lastReceivedSequenceNumber != -1 && (packet.getSequenceNumber() - lastReceivedSequenceNumber != 1)) {
			log.debug("New packet has a wrong sequence number " + packet.getSequenceNumber());
			resetConverter();
			return result;
		}
		lastReceivedSequenceNumber = packet.getSequenceNumber();

		byte[] payload = packet.getPayload();
		int nalType = payload[0] & 0x1f;
		switch (nalType) {
		case 7: // SPS
			sps = payload;
			log.debug("SPS received: {}", sps);
			break;
		case 8: // PPS
			pps = payload;
			log.debug("PPS received: {}", pps);
			break;
		default:
			if (payload.length > 1) {
				if (nalType == 24) { // for aggregated SPS and PPS
					payload = Arrays.copyOfRange(payload, 1, payload.length);
					while (payload.length > 0) {
						int size = payload[0] & 0xff;
						payload = Arrays.copyOfRange(payload, 2, payload.length);
						byte[] naldata = Arrays.copyOf(payload, size);
						payload = Arrays.copyOfRange(payload, size, payload.length);
						int nt = naldata[0] & 0x1f;
						switch (nt) {
						case 7:
							sps = naldata;
							log.debug("SPS received: {}", sps);
							break;
						case 8:
							pps = naldata;
							log.debug("PPS received: {}", pps);
							break;
						default:
							break;
						}
					}
				}

				if (nalType == 1 || nalType == 5 || nalType == 28 || nalType == 24) {
					packetsQueue.add(new RtpPacketWrapper(packet, nalType));
				}
			}
			break;
		}

		if (packetsQueue.size() > 1) {
			RtpPacket last = packetsQueue.get(packetsQueue.size() - 1).packet;
			RtpPacket preLast = packetsQueue.get(packetsQueue.size() - 2).packet;
			if (last.getTimestamp() != preLast.getTimestamp()) {
				log.debug("Clearing queue since new packet has different ts. old ts=" + preLast.getTimestamp() +
						" new ts=" + last.getTimestamp());
				packetsQueue.clear();
			}
		}

		// marker means the end of the frame
		if (packet.getPacket()[1] < 0 // packet.hasMarker() bug workaround
				&& !packetsQueue.isEmpty()) {
			int realNri = 0;
			nalType = 0;
			List<ByteArrayBuilder> payloads = new ArrayList<>();
			ByteArrayBuilder newdata = null;
			List<ByteArrayBuilder> pendingData = new ArrayList<>();

			for (RtpPacketWrapper q: packetsQueue) {
				int length = 0;
				switch (q.nalType) {
				case 1:
				case 5:
					if (newdata == null) {
						nalType = q.nalType;
						// first byte: 0x17 for intra-frame, 0x27 for non-intra frame
						// second byte: 0x01 for picture data
						newdata = new ByteArrayBuilder(new byte[]{(byte) (q.nalType == 5? 0x17: 0x27), 1, 0, 0, 0});
					}
					length = q.packet.getPayload().length;
					newdata.putArray((byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length);
					newdata.putArray(q.packet.getPayload());
					break;
				case 24:
					payload = Arrays.copyOfRange(payload, 1, payload.length);
					while (payload.length > 0) {
						int size = payload[1] & 0xff;
						payload = Arrays.copyOfRange(payload, 2, payload.length);
						byte[] naldata = Arrays.copyOf(payload, size);
						payload = Arrays.copyOfRange(payload, size, payload.length);
						int nt = naldata[0] & 0x1f;
						if (nt == 5 || nt == 1) {
							if (newdata == null) {
								nalType = nt;
								// first byte: 0x17 for intra-frame, 0x27 for non-intra frame
								// second byte: 0x01 for picture data
								newdata = new ByteArrayBuilder(new byte[]{(byte) (nt == 5? 0x17: 0x27), 1, 0, 0, 0});
							}
							length = naldata.length;
							newdata.putArray((byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length);
							newdata.putArray(naldata);
						}
					}
					break;
				case 28:
					boolean start = (q.packet.getPayload()[1] & 0x80) == 0x80;
					boolean finish = (q.packet.getPayload()[1] & 0x40) == 0x40;
					if (start && finish) {
						log.warn("Packets with nal unit type 28 must not have start and finish bits together");
						continue;
					}
					if (start) {
						fuaStartedAndNotFinished = true;
					}
					if (!fuaStartedAndNotFinished) {
						log.warn("Started packet sequence for nal unit type 28 not found");
						continue;
					}
					if (newdata == null) {
						nalType = q.packet.getPayload()[1] & 0x1f;
						realNri = q.packet.getPayload()[0] & 0x60;
						// first byte: 0x17 for intra-frame, 0x27 for non-intra frame
						// second byte: 0x01 for picture data
						newdata = new ByteArrayBuilder(new byte[]{(byte) (nalType == 5? 0x17: 0x27), 1, 0, 0, 0});
					}
					pendingData.add(new ByteArrayBuilder(Arrays.copyOfRange(q.packet.getPayload(), 2, q.packet.getPayload().length)));
					if (finish) {
						ByteArrayBuilder remaining = new ByteArrayBuilder((byte) (nalType | realNri));
						for (ByteArrayBuilder pd: pendingData) {
							remaining.putArray(pd.buildArray());
						}
						pendingData.clear();
						length = remaining.getLength();
						newdata.putArray((byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length);
						newdata.putArray(remaining.buildArray());
						fuaStartedAndNotFinished = false;
					} else {
						continue;
					}
					break;
				default:
					break;
				}
			}
			packetsQueue.clear();

			if (newdata != null) {
				payloads.add(newdata);
			}

			if (!sentSeq && nalType != 5 && pps.length > 0 && sps.length > 0 || sps.length == 0 || pps.length == 0) {
				if (System.currentTimeMillis() - lastFIRTime > 5000) {
					lastFIRTime = System.currentTimeMillis();
					requestFIR();
				}
			} else {
				if (pps.length > 0 && sps.length > 0 && !sentSeq && nalType == 5) {
					sentSeq = true;
				}

				// calculate timestamp
				if (startTs == -1) {
					startTs = packet.getTimestamp();
				}
				if (startTm == -1) {
					startTm = System.currentTimeMillis() - startRelativeTime;
				}

				long tm = startTm + (packet.getTimestamp() - startTs) / (codec.getSampleRate() / 1000); //FIXME was 132
				if (nalType == 5 && payloads.size() > 0) {
					ByteArrayBuilder data = new ByteArrayBuilder();
					// first byte: 0x17 for intra-frame
					// second byte: 0x00 for configuration data
					data.putArray(new byte[]{0x17, 0, 0, 0, 0, 1});
					data.putArray(Arrays.copyOfRange(sps, 1, 4));
					data.putArray((byte) 0xff, (byte) 0xe1, (byte) (sps.length >>> 8), (byte) sps.length);
					data.putArray(sps);
					data.putArray((byte) 1, (byte) (pps.length >>> 8), (byte) pps.length);
					data.putArray(pps);
					payloads.add(0, data);
				}

				for (ByteArrayBuilder bba: payloads) {
					result.add(new RTMPPacketInfo(bba.buildArray(), tm));
				}
			}
		}

		return result;
	}

	protected void requestFIR() {
		sipTransport.requestFIR();
	}

	public static class RTMPPacketInfo {

		public byte[] data;
		public long ts;

		public RTMPPacketInfo(byte[] data, long ts) {
			super();
			this.data = data;
			this.ts = ts;
		}

	}

	private static class ByteArrayBuilder {

		private final List<BuilderElement> arrays;
		private int totalLength = 0;

		public ByteArrayBuilder() {
			arrays = new ArrayList<>();
		}

		public ByteArrayBuilder(byte...array) {
			this();
			this.putArray(array);
		}

		public void putArray(byte... array) {
			if (array.length == 0) return;
			arrays.add(new BuilderElement(array));
			totalLength += array.length;
		}

		public int getLength() {
			return totalLength;
		}

		public byte[] buildArray() {
			if (totalLength == 0) return new byte[0];
			byte[] result = new byte[totalLength];
			int pos = 0;
			for (BuilderElement e: arrays) {
				System.arraycopy(e.array, 0, result, pos, e.array.length);
				pos += e.array.length;
			}

			if (arrays.size() > 1) {
				clear();
				putArray(result);
			}

			return result;
		}

		public void clear() {
			arrays.clear();
			totalLength = 0;
		}

		private static class BuilderElement {

			public final byte[] array;

			public BuilderElement(byte[] array) {
				super();
				this.array = array;
			}

		}

	}

	private static class RtpPacketWrapper {

		public final RtpPacket packet;

		public final int nalType;

		public RtpPacketWrapper(RtpPacket packet, int nalType) {
			this.packet = packet;
			this.nalType = nalType;
		}

	}

}
