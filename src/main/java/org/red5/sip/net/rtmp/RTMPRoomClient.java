package org.red5.sip.net.rtmp;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.mina.core.RuntimeIoException;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.openmeetings.db.entity.room.StreamClient;
import org.red5.client.net.rtmp.BaseRTMPClientHandler;
import org.red5.client.net.rtmp.ClientExceptionHandler;
import org.red5.client.net.rtmp.INetStreamEventHandler;
import org.red5.client.net.rtmp.RTMPClient;
import org.red5.io.utils.ObjectMap;
import org.red5.server.api.event.IEvent;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.api.service.IServiceCall;
import org.red5.server.api.service.IServiceInvoker;
import org.red5.server.net.ICommand;
import org.red5.server.net.rtmp.Channel;
import org.red5.server.net.rtmp.RTMPConnection;
import org.red5.server.net.rtmp.codec.RTMP;
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.ChunkSize;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.net.rtmp.status.StatusCodes;
import org.red5.server.service.Call;
import org.red5.server.stream.message.RTMPMessage;
import org.red5.sip.app.IMediaReceiver;
import org.red5.sip.app.IMediaSender;
import org.red5.sip.app.ISipNumberListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RTMPRoomClient extends RTMPClient implements INetStreamEventHandler, ClientExceptionHandler,
		IPendingServiceCallback, IMediaReceiver {
	private static final Logger log = LoggerFactory.getLogger(RTMPRoomClient.class);
	private static final int MAX_RETRY_NUMBER = 100;
	private static final int UPDATE_MS = 3000;

	private Set<String> broadcastIds = new HashSet<>();
	private Map<String, Double> clientStreamMap = new HashMap<>();
	private String broadCastId = null;
	private IMediaSender audioSender;
	private IMediaSender videoSender;
	private IoBuffer audioBuffer;
	private IoBuffer videoBuffer;
	private Double publishStreamId = null;
	private boolean reconnect = true;
	private int retryNumber = 0;
	private boolean micMuted = false;
	private boolean silence = true;
	private String sipNumber = null;
	private ISipNumberListener sipNumberListener = null;
	private long lastSendActivityMS = 0L;
	private boolean videoReceivingEnabled = false;
	private boolean streamCreated = false;
	private final Runnable updateTask = new Runnable() {
		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(UPDATE_MS);
					updateSipTransport();
				} catch (InterruptedException e) {
					log.debug("updateThread was interrupted", e);
					return;
				}
			}
		}
	};
	private Runnable afterCallConnectedTask;
	private boolean callConnected;
	private Thread updateThread = null;

	protected enum ServiceMethod {
		connect, listRoomBroadcast, createStream, updateSipTransport, sendMessage, getSipNumber
	}

	final private long roomId;
	final private String context;
	final private String host;
	final private String uid;
	private Number activeVideoStreamID = null;
	private String destination;
	private int sipUsersCount;

	public RTMPRoomClient(String host, String context, String uid, long roomId) {
		super();
		this.roomId = roomId;
		this.context = context;
		this.host = host;
		this.uid = uid;
		this.setServiceProvider(this);
		this.setExceptionHandler(this);
		Field serviceInvoker = null;
		try {
			serviceInvoker = BaseRTMPClientHandler.class.getDeclaredField("serviceInvoker");
			serviceInvoker.setAccessible(true);
			serviceInvoker.set(this, new IServiceInvoker() {
				@Override
				public boolean invoke(IServiceCall call, IScope iScope) {
					call.setStatus(Call.STATUS_SUCCESS_VOID);
					return true;
				}

				@Override
				public boolean invoke(IServiceCall call, Object o) {
					call.setStatus(Call.STATUS_SUCCESS_VOID);
					return true;
				}
			});
		} catch (NoSuchFieldException|IllegalAccessException e) {
			log.error("Unexpected Exception", e);
		}
	}

	public void start() {
		log.debug("Connecting. Host: {}, Port: {}, Context: {}, RoomID: {}", host, "1935", context, roomId);
		stop();
		reconnect = true;
		Map<String, Object> params = makeDefaultConnectionParams(host, 1935, String.format("%s/%s", context, roomId));
		Map<String, Object> args = new HashMap<>();
		args.put("uid", UUID.randomUUID().toString());
		args.put("sid", uid);
		connect(host, 1935, params, this, new Object[]{args});
	}

	public void setSipNumberListener(ISipNumberListener sipNumberListener) {
		this.sipNumberListener = sipNumberListener;
	}

	public void init(String destination) {
		this.destination = destination;
		streamCreated = false;
		this.listBroadcastIds();
	}

	public void stop() {
		reconnect = false;
		if (conn != null) {
			disconnect();
		}
		publishStreamId = null;
	}

	@Override
	public void setAudioSender(IMediaSender audioSender) {
		this.audioSender = audioSender;
	}

	@Override
	public void setVideoSender(IMediaSender videoSender) {
		this.videoSender = videoSender;
	}

	protected void listBroadcastIds() {
		invoke("listRoomBroadcast", this);
	}

	public Number getActiveVideoStreamID() {
		return activeVideoStreamID;
	}

	public void setActiveVideoStreamID(Number activeVideoStreamID) {
		this.activeVideoStreamID = activeVideoStreamID;
	}

	private void createPlayStream(String broadCastId) {
		log.debug("create play stream");
		broadcastIds.add(broadCastId);
		IPendingServiceCallback wrapper = new CreatePlayStreamCallBack(broadCastId);
		invoke("createStream", null, wrapper);
	}

	private class CreatePlayStreamCallBack implements IPendingServiceCallback {
		private String _broadCastId;

		public CreatePlayStreamCallBack(String broadCastId) {
			this._broadCastId = broadCastId;
		}

		@Override
		public void resultReceived(IPendingServiceCall call) {

			Double streamId = (Double) call.getResult();

			if (conn != null && streamId != null
					&& (publishStreamId == null || !streamId.equals(publishStreamId))) {
				clientStreamMap.put(_broadCastId, streamId);
				PlayNetStream stream = new PlayNetStream(audioSender, videoSender, RTMPRoomClient.this);
				stream.setConnection(conn);
				stream.setStreamId(streamId.intValue());
				conn.addClientStream(stream);
				play(streamId, _broadCastId, -2000, -1000);
				stream.start();
			}
		}
	}

	protected void getSipNumber() {
		conn.invoke("getSipNumber", new Object[] { roomId }, this);
	}

	public int getSipUsersCount() {
		return sipUsersCount;
	}

	private void setSipUsersCount(int sipUsersCount) {
		this.sipUsersCount = sipUsersCount;
	}

	protected void startStreaming() {
		// red5 -> SIP
		for (String broadCastId : broadcastIds) {
			if (!broadCastId.equals(this.broadCastId)) {
				createPlayStream(broadCastId);
			}
		}
	}

	protected void updateSipTransport() {
		conn.invoke("updateSipTransport", this);
	}

	@Override
	public void connectionOpened(RTMPConnection conn) {
		log.debug("RTMP Connection opened");
		super.connectionOpened(conn);
		retryNumber = 0;
	}

	private void reconnect() {
		stop();
		if (reconnect && ++retryNumber < MAX_RETRY_NUMBER) {
			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				log.error("Reconnection pause was interrupted", e);
			}
			log.debug("Try reconnect...");
			this.start();
		} else {
			if (updateThread != null && updateThread.isAlive()) {
				updateThread.interrupt();
			}
			updateThread = null;
		}
	}

	@Override
	public void connectionClosed(RTMPConnection conn) {
		log.debug("RTMP Connection closed");
		super.connectionClosed(conn);
		reconnect();
	}

	@Override
	protected void onCommand(RTMPConnection conn, Channel channel, Header source, ICommand command) {
		super.onCommand(conn, channel, source, command);
		if (!(command instanceof Notify)) {
			return;
		}
		Notify invoke = (Notify)command;
		if (invoke.getType() == IEvent.Type.STREAM_DATA) {
			return;
		}

		if (invoke.getType() == IEvent.Type.STREAM_DATA) {
			return;
		}
		try {
			String methodName = invoke.getCall().getServiceMethodName();
			InvokeMethods method;
			try {
				method = InvokeMethods.valueOf(methodName);
			} catch (IllegalArgumentException e) {
				return;
			}
			switch (method) {
			case receiveExclusiveAudioFlag:
				receiveExclusiveAudioFlag(StreamClient.class.cast(invoke.getCall().getArguments()[0]));
				break;
			case sendVarsToMessageWithClient:
				sendVarsToMessageWithClient(invoke.getCall().getArguments()[0]);
				break;
			case newStream:
				newStream(StreamClient.class.cast(invoke.getCall().getArguments()[0]));
				break;
			case closeStream:
				closeStream(StreamClient.class.cast(invoke.getCall().getArguments()[0]));
				break;
			default:
				log.debug("Method not found: {} , args number: {}", method, invoke.getCall().getArguments().length);
			}
		} catch (ClassCastException e) {
			log.error("onInvoke error", e);
		}
	}

	@Override
	public void handleException(Throwable throwable) {
		log.error("Exception was:", throwable);
		if (throwable instanceof RuntimeIoException) {
			reconnect();
		}

	}

	/******************************************************************************************************************/
	/** Serive provider methods */
	/******************************************************************************************************************/

	enum InvokeMethods {
		receiveExclusiveAudioFlag, sendVarsToMessageWithClient, closeStream, newStream
	}

	public void receiveExclusiveAudioFlag(StreamClient client) {
		log.debug("receiveExclusiveAudioFlag: {}", client.getUid());
		this.micMuted = !client.getUid().equals(this.uid);
		log.info("Mic switched: {}", this.micMuted);
	}

	public void sendVarsToMessageWithClient(Object message) {
		if (message instanceof Map) {
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> map = (Map<String, Object>)message;
				@SuppressWarnings("unchecked")
				Map<String, Object> msgValue = (Map<String, Object>)map.get("message");
				if ("kick".equals(map.get(0)) || "kick".equals(msgValue.get(0))) {
					log.info("Kicked by moderator. Reconnect");
					this.conn.close();
				} else if ("updateMuteStatus".equals(msgValue.get(0))) {
					StreamClient client = (StreamClient) msgValue.get(1);
					if (this.uid.equals(client.getUid())) {
						log.info("Mic switched: {}", client.isMicMuted());
						this.micMuted = client.isMicMuted();
					}
				}
			} catch (Exception ignored) {
			}
		}
		log.debug("sendVarsToMessageWithClient:" + message.toString());
	}

	public void closeStream(StreamClient client) {
		log.debug("closeStream:" + client.getBroadCastID());
		Double streamId = clientStreamMap.get(client.getBroadCastID());
		if (streamId != null) {
			clientStreamMap.remove(client.getBroadCastID());
			conn.getStreamById(streamId).stop();
			conn.removeClientStream(streamId);
			conn.deleteStreamById(streamId);
			if (streamId.equals(getActiveVideoStreamID())) {
				setActiveVideoStreamID(-1);
			}
		}
	}

	public void newStream(StreamClient client) {
		log.debug("newStream:" + client.getBroadCastID());
		if (broadcastIds.contains(client.getBroadcastId())) {
			closeStream(client);
		}
		createPlayStream("" + client.getBroadCastID()); //TODO should be UID
	}

	private synchronized Runnable getAfterCallConnectedTask() {
		return afterCallConnectedTask;
	}

	private synchronized void setAfterCallConnectedTask(Runnable afterCallConnectedTask) {
		this.afterCallConnectedTask = afterCallConnectedTask;
	}

	public void onCallConnected() {
		Runnable task = getAfterCallConnectedTask();
		if (task != null) {
			task.run();
		}
		callConnected = true;
	}

	/******************************************************************************************************************/

	@Override
	public void resultReceived(IPendingServiceCall call) {
		log.trace("service call result: " + call);
		ServiceMethod method;
		try {
			method = ServiceMethod.valueOf(call.getServiceMethodName());
		} catch (IllegalArgumentException e) {
			log.error("Unknown service method: " + call.getServiceMethodName());
			return;
		}
		switch (method) {
			case connect:
				log.info("connect");
				this.getSipNumber();
				break;
			case listRoomBroadcast:
				log.info("listRoomBroadcast");
				final IPendingServiceCall fcall = call;
				Runnable startStreamingTask = new Runnable() {

					@SuppressWarnings("unchecked")
					@Override
					public void run() {
						log.debug("startStreamingTask.run()");
						if (fcall.getResult() instanceof Collection) {
							for (String bId : (Collection<String>) fcall.getResult()) {
								RTMPRoomClient.this.broadcastIds.add(bId);
							}
						}
						RTMPRoomClient.this.startStreaming();
					}

				};
				if (callConnected) {
					startStreamingTask.run();
				} else {
					setAfterCallConnectedTask(startStreamingTask);
				}
				break;
			case createStream:
				log.info("createStream");
				publishStreamId = (Double) call.getResult();
				this.broadCastId = UUID.randomUUID().toString();
				publish(publishStreamId, broadCastId, "live", this);
				// SIP -> red5
				if (!streamCreated) {
					createStream(this);
					streamCreated = true;
				}
				updateThread = new Thread(updateTask, "RTMPRoomClient updateThread");
				updateThread.start();
				break;
			case updateSipTransport:
				log.debug("updateSipTransport");
				setSipUsersCount(((Number) call.getResult()).intValue());
				break;
			case getSipNumber:
				log.info("getSipNumber");
				if (call.getResult() instanceof String) {
					sipNumber = (String) call.getResult();
					if (sipNumberListener != null) {
						sipNumberListener.onSipNumber(sipNumber);
					}
				} else {
					log.error("getSipNumber invalid result: " + call.getResult());
				}
				break;
			default:
				break;
		}
	}

	public void soundActivity() {
		Object[] message = new Object[] { "audioActivity", !silence, this.uid };
		conn.invoke("sendMessage", message, this);
	}

	public void onStatus(Object obj) {
		log.debug("onStatus: " + obj.toString());
	}

	@Override
	public void onStreamEvent(Notify notify) {
		log.debug("onStreamEvent " + notify);

		@SuppressWarnings("unchecked")
		ObjectMap<String, Object> map = (ObjectMap<String, Object>) notify.getCall().getArguments()[0];
		String code = (String) map.get("code");

		if (StatusCodes.NS_PUBLISH_START.equals(code)) {
			log.debug("onStreamEvent Publish start");
		}
	}

	@Override
	public synchronized void setVideoReceivingEnabled(boolean enable) {
		//TODO check this
		/*
		if (enable && !videoReceivingEnabled) {
			setUserAVSettings("av");
		} else if (!enable && videoReceivingEnabled) {
			setUserAVSettings("a");
		}
		*/
		this.videoReceivingEnabled = enable;
	}

	@Override
	public synchronized boolean isVideoReceivingEnabled() {
		return videoReceivingEnabled;
	}

	@Override
	public void pushAudio(byte[] audio, long ts, int codec) throws IOException {
		if (micMuted) {
			return;
		}

		boolean silence = true;
		for (byte anAudio : audio) {
			if (anAudio != -1 && anAudio != -2 && anAudio != 126) {
				silence = false;
				break;
			}
		}
		if (silence != this.silence && lastSendActivityMS + 500 < System.currentTimeMillis()) {
			lastSendActivityMS = System.currentTimeMillis();
			this.silence = silence;
			soundActivity();
		}

		if (silence) {
			log.trace("Silence...");
			return;
		}

		if (publishStreamId == null) {
			return;
		}
		if (audioBuffer == null || (audioBuffer.capacity() < audio.length + 1 && !audioBuffer.isAutoExpand())) {
			audioBuffer = IoBuffer.allocate(1 + audio.length);
			audioBuffer.setAutoExpand(true);
		}

		audioBuffer.clear();

		audioBuffer.put((byte) codec); // first byte 2 mono 5500; 6 mono 11025; 22
		// mono 11025 adpcm 82 nellymoser 8000 178
		// speex 8000
		audioBuffer.put(audio);

		audioBuffer.flip();

		RTMPMessage message = RTMPMessage.build(new AudioData(audioBuffer), (int)ts);
		if (log.isTraceEnabled()) {
			log.trace("+++ " + message.getBody());
		}
		publishStreamData(publishStreamId, message);
	}

	@Override
	public void pushVideo(byte[] video, long ts) throws IOException {
		if(publishStreamId == null) {
			log.debug("publishStreamId == null !!!");
			return;
		}
		if (videoBuffer == null || (videoBuffer.capacity() < video.length && !videoBuffer.isAutoExpand())) {
			videoBuffer = IoBuffer.allocate(video.length);
			videoBuffer.setAutoExpand(true);
		}

		videoBuffer.clear();
		videoBuffer.put(video);
		videoBuffer.flip();

		RTMPMessage message = RTMPMessage.build(new VideoData(videoBuffer), (int)ts);
		if (log.isTraceEnabled()) {
			log.trace("+++ {} data: {}", message.getBody(), video);
		}
		publishStreamData(publishStreamId, message);
	}

	// this method is overrided to avoid red5 chunkSize issue
	@Override
	protected void onChunkSize(RTMPConnection conn, Channel channel, Header source, ChunkSize chunkSize) {
		log.debug("onChunkSize");
		// set read and write chunk sizes
		RTMP state = conn.getState();
		state.setReadChunkSize(chunkSize.getSize());
		log.info("ChunkSize is not fully implemented: {}", chunkSize);
	}

	public String getDestination() {
		return destination;
	}

	public void setDestination(String destination) {
		this.destination = destination;
	}
}
