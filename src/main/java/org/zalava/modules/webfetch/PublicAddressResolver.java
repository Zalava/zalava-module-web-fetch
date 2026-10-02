package org.zalava.modules.webfetch;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
interface PublicAddressResolver {

  void requirePublic(String host) throws UnknownHostException;

  static PublicAddressResolver system() {
    return host -> {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (!isPublic(address)) {
          throw new IllegalArgumentException("URL host must resolve to a public address");
        }
      }
    };
  }

  private static boolean isPublic(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    if (bytes.length == 4) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      return first != 0
          && first != 10
          && first != 127
          && first < 224
          && !(first == 100 && second >= 64 && second <= 127)
          && !(first == 169 && second == 254)
          && !(first == 172 && second >= 16 && second <= 31)
          && !(first == 192 && second == 168);
    }
    return (bytes[0] & 0xfe) != 0xfc;
  }
}
