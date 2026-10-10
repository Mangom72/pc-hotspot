package kr.pc.hotspot;
interface IWifiConnector {
    void destroy() = 16777114;
    int connect(String ssid, String password) = 1;
    boolean connected(String ssid) = 2;
}
