package io.flutter.plugin.common;
public class MethodChannel {
  public MethodChannel(BinaryMessenger m, String name){}
  public interface Result { void success(Object result); void error(String code, String message, Object details); void notImplemented(); }
  public interface MethodCallHandler { void onMethodCall(MethodCall call, Result result); }
  public void setMethodCallHandler(MethodCallHandler h){}
  public void invokeMethod(String method, Object arguments){}
}
