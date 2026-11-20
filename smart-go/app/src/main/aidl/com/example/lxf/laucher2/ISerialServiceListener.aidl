package com.example.lxf.laucher2;

// Wire order matches installed launcher v14; the factory uses the first six.
interface ISerialServiceListener {
    void onReceiveNormalData(String data);
    void onReceiveErrorData(String data);
    void onSendData(String data, boolean success);
    void onConnectSuccess();
    void onConnectFail(int connectNum);
    void onConnectError();
    void onClose();
}
