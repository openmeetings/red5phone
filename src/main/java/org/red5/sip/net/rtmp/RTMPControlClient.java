package org.red5.sip.net.rtmp;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.mina.core.RuntimeIoException;
import org.red5.client.net.rtmp.ClientExceptionHandler;
import org.red5.client.net.rtmp.RTMPClient;
import org.red5.server.api.service.IPendingServiceCall;
import org.red5.server.api.service.IPendingServiceCallback;
import org.red5.server.net.rtmp.RTMPConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class RTMPControlClient extends RTMPClient implements ClientExceptionHandler, IPendingServiceCallback {
	private static final Logger log = LoggerFactory.getLogger(RTMPControlClient.class);
	private static final int UPDATE_MS = 10000;

	private RTMPConnection conn;
	private final String host;
	private final String context;
	private boolean reconnect;
	private Set<Double> activeRooms = new HashSet<>();

	protected enum ServiceMethod {
		connect, getActiveRoomIds
	}
	private final Runnable updateTask = new Runnable() {
		@Override
		public void run() {
			while (true) {
				try {
					Thread.sleep(UPDATE_MS);
					getActiveRoomIds();
				} catch (InterruptedException e) {
					log.debug("updateThread was interrupted", e);
					return;
				}
			}
		}
	};
	private Thread updateThread = null;

	public RTMPControlClient(String host, String context) {
		super();
		this.host = host;
		this.context = context;
	}

	public void start() {
		log.debug("Connecting. Host: {}, Port: {}, Context: {}", host, "1935", context);
		stop();
		reconnect = true;
		Map<String, Object> params = makeDefaultConnectionParams(host, 1935, String.format("%s/hibernate", context));
		Map<String, Object> args = new HashMap<>();
		args.put("uid", "noclient");
		connect(host, 1935, params, this, new Object[]{args});
	}

	public void stop() {
		reconnect = false;
		if (conn != null) {
			disconnect();
		}
	}

	@Override
	public void connectionOpened(RTMPConnection conn) {
		log.debug("RTMP Connection opened");
		super.connectionOpened(conn);
		this.conn = conn;
	}

	private void reconnect() {
		if (reconnect) {
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
	public void handleException(Throwable throwable) {
		log.error("Exception was: ", throwable);
		if (throwable instanceof RuntimeIoException) {
			reconnect();
		}
	}

	private void getActiveRoomIds() {
		conn.invoke("getActiveRoomIds", this);
	}

	@Override
	public void resultReceived(IPendingServiceCall call) {
		log.debug("service call result: " + call);
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
			getActiveRoomIds();
			updateThread = new Thread(updateTask, "RTMPControlClient updateThread");
			updateThread.start();
			break;
		case getActiveRoomIds:
			log.debug("getActiveRoomIds");
			if (call.getResult() instanceof Collection) {
				@SuppressWarnings("unchecked")
				Collection<Double> newActiveRooms = (Collection<Double>)call.getResult();
				for (Double id : newActiveRooms) {
					if (!this.activeRooms.contains(id)) {
						this.activeRooms.add(id);
						log.debug("Start room client, id: " + id);
						startRoomClient(id.longValue());
					}
				}
				for (Double id : this.activeRooms) {
					if (!newActiveRooms.contains(id)) {
						log.info("Stop room client, id: " + id);
						this.activeRooms.remove(id);
						stopRoomClient(id.longValue());
					}
				}
			} else {
				for (Double id : this.activeRooms) {
					log.info("Stop room client, id: " + id);
					this.activeRooms.remove(id);
					stopRoomClient(id.longValue());
				}
			}
			break;
		}
	}

	protected abstract void startRoomClient(long roomId);

	protected abstract void stopRoomClient(long roomId);
}
