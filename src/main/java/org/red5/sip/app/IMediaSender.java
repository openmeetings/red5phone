package org.red5.sip.app;

public interface IMediaSender {

	IMediaStream createStream(Number streamId);

	void deleteStream(Number streamId);

	void start();

	void halt();

}
