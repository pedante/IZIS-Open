package com.example.lxf.laucher2;
import com.example.lxf.laucher2.ISerialServiceListener;

// Interoperability subset: these are transactions 1 through 3 of the existing ABI.
// Transaction 3 is the inspected manufacturer write operation. No later operations exposed.
interface ISerialService {
    void registerListener(ISerialServiceListener listener);
    void unRegisterListener(ISerialServiceListener listener);
    void writeAndFlush(String commend);
}
