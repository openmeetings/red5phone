package org.red5.sip.net.rtmp;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.server.api.event.IEvent;
import org.red5.server.api.event.IEventDispatcher;
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.SerializeUtils;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.event.VideoData.FrameType;
import org.red5.server.stream.AbstractClientStream;
import org.red5.server.stream.IStreamData;
import org.red5.sip.app.IMediaSender;
import org.red5.sip.app.IMediaStream;
import org.red5.sip.net.rtp.RTPVideoStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayNetStream extends AbstractClientStream implements IEventDispatcher {
	private static Logger log = LoggerFactory.getLogger(PlayNetStream.class);

	private int audioTs = 0;

	private IMediaSender audioSender;

	private IMediaStream audioStream;
	
	private IMediaSender videoSender;

	private RTPVideoStream videoStream;
	
	private RTMPRoomClient client;
	
	private Number currentStreamID = null;
	
	private boolean keyframeReceived = false;

	public PlayNetStream(IMediaSender audioSender, IMediaSender videoSender, RTMPRoomClient client) {
		this.audioSender = audioSender;
		this.videoSender = videoSender;
		this.client = client;
	}

	public void close() {
		if (audioSender != null) {
			audioSender.deleteStream(getStreamId());
		}
		if (videoSender != null) {
			videoSender.deleteStream(getStreamId());
		}
	}

	public void start() {
		if (audioSender != null) {
			audioStream = audioSender.createStream(getStreamId());
		}
		if (videoSender != null) {
			videoStream = (RTPVideoStream) videoSender.createStream(getStreamId());
		}
	}

	public void stop() {
		close();
		if (audioStream != null) {
			audioStream.stop();
		}
		if (videoStream != null) {
			videoStream.stop();
		}
	}
	
	public void dispatchEvent(IEvent event) {

		if (!(event instanceof IRTMPEvent)) {
			log.debug("skipping non rtmp event: " + event);
			return;
		}

		IRTMPEvent rtmpEvent = (IRTMPEvent) event;

		if (!(rtmpEvent instanceof IStreamData)) {
			log.debug("skipping non stream data");
			return;
		}

		if (rtmpEvent.getHeader().getSize() == 0) {
			log.debug("skipping event where size == 0");
			return;
		}

		if (rtmpEvent instanceof VideoData) {
			Number newStreamId = client.getActiveVideoStreamID();
			if (newStreamId == null) {
				newStreamId = rtmpEvent.getHeader().getStreamId();
				client.setActiveVideoStreamID(newStreamId);
			}
			if (!rtmpEvent.getHeader().getStreamId().equals(newStreamId)) {
				log.trace("ignoring stream id=" + rtmpEvent.getHeader().getStreamId() + " current stream is " + newStreamId);
				return;
			}
			
			if (!newStreamId.equals(currentStreamID)) {
				log.debug("switching video to a new stream: " + newStreamId);
				currentStreamID = newStreamId;
				if (videoStream != null) {
					videoStream.getConverter().resetConverter();
				}
			}
			
			if (((VideoData) rtmpEvent).getFrameType() == FrameType.KEYFRAME) {
				keyframeReceived = true;
			}
			
			if (!keyframeReceived) {
				log.debug("Keyframe is not received. Packet is ignored.");
				return;
			}
			
			int videoTs = rtmpEvent.getTimestamp();
			IoBuffer videoData = ((VideoData) rtmpEvent).getData().asReadOnlyBuffer();
			videoData.reset();
			byte[] data = SerializeUtils.ByteBufferToByteArray(videoData);
			
			try {
				if (videoStream != null) {
					videoStream.send(videoTs, data, 0, data.length);
				}
			} catch (Exception e) {
				log.error("PlayNetStream dispatchEvent exception ", e);
			}
		} else if (rtmpEvent instanceof AudioData) {
			audioTs = rtmpEvent.getTimestamp();

			IoBuffer audioData = ((AudioData) rtmpEvent).getData().asReadOnlyBuffer();
			byte[] data = SerializeUtils.ByteBufferToByteArray(audioData);

			try {
				if (audioStream != null) {
					audioStream.send(audioTs, data, 1, data.length - 1);
				}
			} catch (Exception e) {
				log.error("PlayNetStream dispatchEvent exception ", e);
			}
		}
	}
}