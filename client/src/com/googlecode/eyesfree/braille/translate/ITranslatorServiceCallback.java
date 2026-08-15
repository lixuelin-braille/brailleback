/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.googlecode.eyesfree.braille.translate;
public interface ITranslatorServiceCallback extends android.os.IInterface
{
  /** Default implementation for ITranslatorServiceCallback. */
  public static class Default implements com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback
  {
    @Override public void onInit(int status) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback interface,
     * generating a proxy if needed.
     */
    public static com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback))) {
        return ((com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback)iin);
      }
      return new com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onInit:
        {
          int _arg0;
          _arg0 = data.readInt();
          this.onInit(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback
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
      @Override public void onInit(int status) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(status);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onInit, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onInit = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  public static final java.lang.String DESCRIPTOR = "com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback";
  public void onInit(int status) throws android.os.RemoteException;
}
