/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.googlecode.eyesfree.braille.display;
/**
 * Callback interface that a braille display client can expose to
 * get information about various braille display events.
 */
public interface IBrailleServiceCallback extends android.os.IInterface
{
  /** Default implementation for IBrailleServiceCallback. */
  public static class Default implements com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
  {
    @Override public void onDisplayConnected(com.googlecode.eyesfree.braille.display.BrailleDisplayProperties displayProperties) throws android.os.RemoteException
    {
    }
    @Override public void onDisplayDisconnected() throws android.os.RemoteException
    {
    }
    @Override public void onInput(com.googlecode.eyesfree.braille.display.BrailleInputEvent inputEvent) throws android.os.RemoteException
    {
    }
    @Override public void onConnectionChangeProgress(java.lang.String descroption) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.googlecode.eyesfree.braille.display.IBrailleServiceCallback interface,
     * generating a proxy if needed.
     */
    public static com.googlecode.eyesfree.braille.display.IBrailleServiceCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.googlecode.eyesfree.braille.display.IBrailleServiceCallback))) {
        return ((com.googlecode.eyesfree.braille.display.IBrailleServiceCallback)iin);
      }
      return new com.googlecode.eyesfree.braille.display.IBrailleServiceCallback.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
      }
      switch (code)
      {
        case TRANSACTION_onDisplayConnected:
        {
          com.googlecode.eyesfree.braille.display.BrailleDisplayProperties _arg0;
          _arg0 = _Parcel.readTypedObject(data, com.googlecode.eyesfree.braille.display.BrailleDisplayProperties.CREATOR);
          this.onDisplayConnected(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onDisplayDisconnected:
        {
          this.onDisplayDisconnected();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onInput:
        {
          com.googlecode.eyesfree.braille.display.BrailleInputEvent _arg0;
          _arg0 = _Parcel.readTypedObject(data, com.googlecode.eyesfree.braille.display.BrailleInputEvent.CREATOR);
          this.onInput(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_onConnectionChangeProgress:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.onConnectionChangeProgress(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void onDisplayConnected(com.googlecode.eyesfree.braille.display.BrailleDisplayProperties displayProperties) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, displayProperties, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onDisplayConnected, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onDisplayDisconnected() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onDisplayDisconnected, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onInput(com.googlecode.eyesfree.braille.display.BrailleInputEvent inputEvent) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, inputEvent, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onInput, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void onConnectionChangeProgress(java.lang.String descroption) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(descroption);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onConnectionChangeProgress, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onDisplayConnected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_onDisplayDisconnected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_onInput = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_onConnectionChangeProgress = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
  }
  public static final java.lang.String DESCRIPTOR = "com.googlecode.eyesfree.braille.display.IBrailleServiceCallback";
  public void onDisplayConnected(com.googlecode.eyesfree.braille.display.BrailleDisplayProperties displayProperties) throws android.os.RemoteException;
  public void onDisplayDisconnected() throws android.os.RemoteException;
  public void onInput(com.googlecode.eyesfree.braille.display.BrailleInputEvent inputEvent) throws android.os.RemoteException;
  public void onConnectionChangeProgress(java.lang.String descroption) throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
