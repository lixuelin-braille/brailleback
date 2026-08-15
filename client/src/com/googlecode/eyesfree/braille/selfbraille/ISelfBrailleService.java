/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.googlecode.eyesfree.braille.selfbraille;
/**
 * Interface for a client to control braille output for a part of the
 * accessibility node tree.
 */
public interface ISelfBrailleService extends android.os.IInterface
{
  /** Default implementation for ISelfBrailleService. */
  public static class Default implements com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService
  {
    @Override public void write(android.os.IBinder clientToken, com.googlecode.eyesfree.braille.selfbraille.WriteData writeData) throws android.os.RemoteException
    {
    }
    @Override public void disconnect(android.os.IBinder clientToken) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService interface,
     * generating a proxy if needed.
     */
    public static com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService))) {
        return ((com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService)iin);
      }
      return new com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService.Stub.Proxy(obj);
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
        case TRANSACTION_write:
        {
          android.os.IBinder _arg0;
          _arg0 = data.readStrongBinder();
          com.googlecode.eyesfree.braille.selfbraille.WriteData _arg1;
          _arg1 = _Parcel.readTypedObject(data, com.googlecode.eyesfree.braille.selfbraille.WriteData.CREATOR);
          this.write(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_disconnect:
        {
          android.os.IBinder _arg0;
          _arg0 = data.readStrongBinder();
          this.disconnect(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService
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
      @Override public void write(android.os.IBinder clientToken, com.googlecode.eyesfree.braille.selfbraille.WriteData writeData) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder(clientToken);
          _Parcel.writeTypedObject(_data, writeData, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_write, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void disconnect(android.os.IBinder clientToken) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder(clientToken);
          boolean _status = mRemote.transact(Stub.TRANSACTION_disconnect, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_write = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_disconnect = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
  }
  public static final java.lang.String DESCRIPTOR = "com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService";
  public void write(android.os.IBinder clientToken, com.googlecode.eyesfree.braille.selfbraille.WriteData writeData) throws android.os.RemoteException;
  public void disconnect(android.os.IBinder clientToken) throws android.os.RemoteException;
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
