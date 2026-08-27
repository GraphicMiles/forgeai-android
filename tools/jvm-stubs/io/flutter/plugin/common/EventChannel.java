package io.flutter.plugin.common;
public class EventChannel {
  public EventChannel(BinaryMessenger m, String name){}
  public interface EventSink { void success(Object event); void error(String code, String message, Object details); void endOfStream(); }
  public interface StreamHandler { void onListen(Object arguments, EventSink events); void onCancel(Object arguments); }
  public void setStreamHandler(StreamHandler h){}
}
