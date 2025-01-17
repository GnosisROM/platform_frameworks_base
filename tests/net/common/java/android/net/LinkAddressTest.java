/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import static android.system.OsConstants.IFA_F_DADFAILED;
import static android.system.OsConstants.IFA_F_DEPRECATED;
import static android.system.OsConstants.IFA_F_OPTIMISTIC;
import static android.system.OsConstants.IFA_F_PERMANENT;
import static android.system.OsConstants.IFA_F_TEMPORARY;
import static android.system.OsConstants.IFA_F_TENTATIVE;
import static android.system.OsConstants.RT_SCOPE_HOST;
import static android.system.OsConstants.RT_SCOPE_LINK;
import static android.system.OsConstants.RT_SCOPE_SITE;
import static android.system.OsConstants.RT_SCOPE_UNIVERSE;

import static com.android.testutils.MiscAsserts.assertEqualBothWays;
import static com.android.testutils.MiscAsserts.assertFieldCountEquals;
import static com.android.testutils.MiscAsserts.assertNotEqualEitherWay;
import static com.android.testutils.ParcelUtils.assertParcelingIsLossless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;
import android.os.SystemClock;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LinkAddressTest {
    @Rule
    public final DevSdkIgnoreRule ignoreRule = new DevSdkIgnoreRule();

    private static final String V4 = "192.0.2.1";
    private static final String V6 = "2001:db8::1";
    private static final InetAddress V4_ADDRESS = InetAddresses.parseNumericAddress(V4);
    private static final InetAddress V6_ADDRESS = InetAddresses.parseNumericAddress(V6);

    @Test
    public void testConstants() {
        // RT_SCOPE_UNIVERSE = 0, but all the other constants should be nonzero.
        assertNotEquals(0, RT_SCOPE_HOST);
        assertNotEquals(0, RT_SCOPE_LINK);
        assertNotEquals(0, RT_SCOPE_SITE);

        assertNotEquals(0, IFA_F_DEPRECATED);
        assertNotEquals(0, IFA_F_PERMANENT);
        assertNotEquals(0, IFA_F_TENTATIVE);
    }

    @Test
    public void testConstructors() throws SocketException {
        LinkAddress address;

        // Valid addresses work as expected.
        address = new LinkAddress(V4_ADDRESS, 25);
        assertEquals(V4_ADDRESS, address.getAddress());
        assertEquals(25, address.getPrefixLength());
        assertEquals(0, address.getFlags());
        assertEquals(RT_SCOPE_UNIVERSE, address.getScope());
        assertTrue(address.isIpv4());

        address = new LinkAddress(V6_ADDRESS, 127);
        assertEquals(V6_ADDRESS, address.getAddress());
        assertEquals(127, address.getPrefixLength());
        assertEquals(0, address.getFlags());
        assertEquals(RT_SCOPE_UNIVERSE, address.getScope());
        assertTrue(address.isIpv6());

        // Nonsensical flags/scopes or combinations thereof are acceptable.
        address = new LinkAddress(V6 + "/64", IFA_F_DEPRECATED | IFA_F_PERMANENT, RT_SCOPE_LINK);
        assertEquals(V6_ADDRESS, address.getAddress());
        assertEquals(64, address.getPrefixLength());
        assertEquals(IFA_F_DEPRECATED | IFA_F_PERMANENT, address.getFlags());
        assertEquals(RT_SCOPE_LINK, address.getScope());
        assertTrue(address.isIpv6());

        address = new LinkAddress(V4 + "/23", 123, 456);
        assertEquals(V4_ADDRESS, address.getAddress());
        assertEquals(23, address.getPrefixLength());
        assertEquals(123, address.getFlags());
        assertEquals(456, address.getScope());
        assertTrue(address.isIpv4());

        // InterfaceAddress doesn't have a constructor. Fetch some from an interface.
        List<InterfaceAddress> addrs = NetworkInterface.getByName("lo").getInterfaceAddresses();

        // We expect to find 127.0.0.1/8 and ::1/128, in any order.
        LinkAddress ipv4Loopback, ipv6Loopback;
        assertEquals(2, addrs.size());
        if (addrs.get(0).getAddress() instanceof Inet4Address) {
            ipv4Loopback = new LinkAddress(addrs.get(0));
            ipv6Loopback = new LinkAddress(addrs.get(1));
        } else {
            ipv4Loopback = new LinkAddress(addrs.get(1));
            ipv6Loopback = new LinkAddress(addrs.get(0));
        }

        assertEquals(InetAddresses.parseNumericAddress("127.0.0.1"), ipv4Loopback.getAddress());
        assertEquals(8, ipv4Loopback.getPrefixLength());

        assertEquals(InetAddresses.parseNumericAddress("::1"), ipv6Loopback.getAddress());
        assertEquals(128, ipv6Loopback.getPrefixLength());

        // Null addresses are rejected.
        try {
            address = new LinkAddress(null, 24);
            fail("Null InetAddress should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress((String) null, IFA_F_PERMANENT, RT_SCOPE_UNIVERSE);
            fail("Null string should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress((InterfaceAddress) null);
            fail("Null string should cause NullPointerException");
        } catch(NullPointerException expected) {}

        // Invalid prefix lengths are rejected.
        try {
            address = new LinkAddress(V4_ADDRESS, -1);
            fail("Negative IPv4 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V6_ADDRESS, -1);
            fail("Negative IPv6 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V4_ADDRESS, 33);
            fail("/33 IPv4 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V4 + "/33", IFA_F_PERMANENT, RT_SCOPE_UNIVERSE);
            fail("/33 IPv4 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}


        try {
            address = new LinkAddress(V6_ADDRESS, 129, IFA_F_PERMANENT, RT_SCOPE_UNIVERSE);
            fail("/129 IPv6 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress(V6 + "/129", IFA_F_PERMANENT, RT_SCOPE_UNIVERSE);
            fail("/129 IPv6 prefix length should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        // Multicast addresses are rejected.
        try {
            address = new LinkAddress("224.0.0.2/32");
            fail("IPv4 multicast address should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}

        try {
            address = new LinkAddress("ff02::1/128");
            fail("IPv6 multicast address should cause IllegalArgumentException");
        } catch(IllegalArgumentException expected) {}
    }

    @Test
    public void testAddressScopes() {
        assertEquals(RT_SCOPE_HOST, new LinkAddress("::/128").getScope());
        assertEquals(RT_SCOPE_HOST, new LinkAddress("0.0.0.0/32").getScope());

        assertEquals(RT_SCOPE_LINK, new LinkAddress("::1/128").getScope());
        assertEquals(RT_SCOPE_LINK, new LinkAddress("127.0.0.5/8").getScope());
        assertEquals(RT_SCOPE_LINK, new LinkAddress("fe80::ace:d00d/64").getScope());
        assertEquals(RT_SCOPE_LINK, new LinkAddress("169.254.5.12/16").getScope());

        assertEquals(RT_SCOPE_SITE, new LinkAddress("fec0::dead/64").getScope());

        assertEquals(RT_SCOPE_UNIVERSE, new LinkAddress("10.1.2.3/21").getScope());
        assertEquals(RT_SCOPE_UNIVERSE, new LinkAddress("192.0.2.1/25").getScope());
        assertEquals(RT_SCOPE_UNIVERSE, new LinkAddress("2001:db8::/64").getScope());
        assertEquals(RT_SCOPE_UNIVERSE, new LinkAddress("5000::/127").getScope());
    }

    private void assertIsSameAddressAs(LinkAddress l1, LinkAddress l2) {
        assertTrue(l1 + " unexpectedly does not have same address as " + l2,
                l1.isSameAddressAs(l2));
        assertTrue(l2 + " unexpectedly does not have same address as " + l1,
                l2.isSameAddressAs(l1));
    }

    private void assertIsNotSameAddressAs(LinkAddress l1, LinkAddress l2) {
        assertFalse(l1 + " unexpectedly has same address as " + l2,
                l1.isSameAddressAs(l2));
        assertFalse(l2 + " unexpectedly has same address as " + l1,
                l1.isSameAddressAs(l2));
    }

    @Test
    public void testEqualsAndSameAddressAs() {
        LinkAddress l1, l2, l3;

        l1 = new LinkAddress("2001:db8::1/64");
        l2 = new LinkAddress("2001:db8::1/64");
        assertEqualBothWays(l1, l2);
        assertIsSameAddressAs(l1, l2);

        l2 = new LinkAddress("2001:db8::1/65");
        assertNotEqualEitherWay(l1, l2);
        assertIsNotSameAddressAs(l1, l2);

        l2 = new LinkAddress("2001:db8::2/64");
        assertNotEqualEitherWay(l1, l2);
        assertIsNotSameAddressAs(l1, l2);


        l1 = new LinkAddress("192.0.2.1/24");
        l2 = new LinkAddress("192.0.2.1/24");
        assertEqualBothWays(l1, l2);
        assertIsSameAddressAs(l1, l2);

        l2 = new LinkAddress("192.0.2.1/23");
        assertNotEqualEitherWay(l1, l2);
        assertIsNotSameAddressAs(l1, l2);

        l2 = new LinkAddress("192.0.2.2/24");
        assertNotEqualEitherWay(l1, l2);
        assertIsNotSameAddressAs(l1, l2);


        // Check equals() and isSameAddressAs() on identical addresses with different flags.
        l1 = new LinkAddress(V6_ADDRESS, 64);
        l2 = new LinkAddress(V6_ADDRESS, 64, 0, RT_SCOPE_UNIVERSE);
        assertEqualBothWays(l1, l2);
        assertIsSameAddressAs(l1, l2);

        l2 = new LinkAddress(V6_ADDRESS, 64, IFA_F_DEPRECATED, RT_SCOPE_UNIVERSE);
        assertNotEqualEitherWay(l1, l2);
        assertIsSameAddressAs(l1, l2);

        // Check equals() and isSameAddressAs() on identical addresses with different scope.
        l1 = new LinkAddress(V4_ADDRESS, 24);
        l2 = new LinkAddress(V4_ADDRESS, 24, 0, RT_SCOPE_UNIVERSE);
        assertEqualBothWays(l1, l2);
        assertIsSameAddressAs(l1, l2);

        l2 = new LinkAddress(V4_ADDRESS, 24, 0, RT_SCOPE_HOST);
        assertNotEqualEitherWay(l1, l2);
        assertIsSameAddressAs(l1, l2);

        // Addresses with the same start or end bytes aren't equal between families.
        l1 = new LinkAddress("32.1.13.184/24");
        l2 = new LinkAddress("2001:db8::1/24");
        l3 = new LinkAddress("::2001:db8/24");

        byte[] ipv4Bytes = l1.getAddress().getAddress();
        byte[] l2FirstIPv6Bytes = Arrays.copyOf(l2.getAddress().getAddress(), 4);
        byte[] l3LastIPv6Bytes = Arrays.copyOfRange(l3.getAddress().getAddress(), 12, 16);
        assertTrue(Arrays.equals(ipv4Bytes, l2FirstIPv6Bytes));
        assertTrue(Arrays.equals(ipv4Bytes, l3LastIPv6Bytes));

        assertNotEqualEitherWay(l1, l2);
        assertIsNotSameAddressAs(l1, l2);

        assertNotEqualEitherWay(l1, l3);
        assertIsNotSameAddressAs(l1, l3);

        // Because we use InetAddress, an IPv4 address is equal to its IPv4-mapped address.
        // TODO: Investigate fixing this.
        String addressString = V4 + "/24";
        l1 = new LinkAddress(addressString);
        l2 = new LinkAddress("::ffff:" + addressString);
        assertEqualBothWays(l1, l2);
        assertIsSameAddressAs(l1, l2);
    }

    @Test
    public void testHashCode() {
        LinkAddress l1, l2;

        l1 = new LinkAddress(V4_ADDRESS, 23);
        l2 = new LinkAddress(V4_ADDRESS, 23, 0, RT_SCOPE_HOST);
        assertNotEquals(l1.hashCode(), l2.hashCode());

        l1 = new LinkAddress(V6_ADDRESS, 128);
        l2 = new LinkAddress(V6_ADDRESS, 128, IFA_F_TENTATIVE, RT_SCOPE_UNIVERSE);
        assertNotEquals(l1.hashCode(), l2.hashCode());
    }

    @Test
    public void testParceling() {
        LinkAddress l;

        l = new LinkAddress(V6_ADDRESS, 64, 123, 456);
        assertParcelingIsLossless(l);

        l = new LinkAddress(V4 + "/28", IFA_F_PERMANENT, RT_SCOPE_LINK);
        assertParcelingIsLossless(l);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testLifetimeParceling() {
        final LinkAddress l = new LinkAddress(V6_ADDRESS, 64, 123, 456, 1L, 3600000L);
        assertParcelingIsLossless(l);
    }

    @Test @IgnoreAfter(Build.VERSION_CODES.Q)
    public void testFieldCount_Q() {
        assertFieldCountEquals(4, LinkAddress.class);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testFieldCount() {
        // Make sure any new field is covered by the above parceling tests when changing this number
        assertFieldCountEquals(6, LinkAddress.class);
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testDeprecationTime() {
        try {
            new LinkAddress(V6_ADDRESS, 64, 0, 456,
                    LinkAddress.LIFETIME_UNKNOWN, 100000L);
            fail("Only one time provided should cause exception");
        } catch (IllegalArgumentException expected) { }

        try {
            new LinkAddress(V6_ADDRESS, 64, 0, 456,
                    200000L, 100000L);
            fail("deprecation time later than expiration time should cause exception");
        } catch (IllegalArgumentException expected) { }

        try {
            new LinkAddress(V6_ADDRESS, 64, 0, 456,
                    -2, 100000L);
            fail("negative deprecation time should cause exception");
        } catch (IllegalArgumentException expected) { }

        LinkAddress addr = new LinkAddress(V6_ADDRESS, 64, 0, 456, 100000L, 200000L);
        assertEquals(100000L, addr.getDeprecationTime());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testExpirationTime() {
        try {
            new LinkAddress(V6_ADDRESS, 64, 0, 456,
                    200000L, LinkAddress.LIFETIME_UNKNOWN);
            fail("Only one time provided should cause exception");
        } catch (IllegalArgumentException expected) { }

        try {
            new LinkAddress(V6_ADDRESS, 64, 0, 456,
                    100000L, -2);
            fail("negative expiration time should cause exception");
        } catch (IllegalArgumentException expected) { }

        LinkAddress addr = new LinkAddress(V6_ADDRESS, 64, 0, 456, 100000L, 200000L);
        assertEquals(200000L, addr.getExpirationTime());
    }

    @Test
    public void testGetFlags() {
        LinkAddress l = new LinkAddress(V6_ADDRESS, 64, 123, RT_SCOPE_HOST);
        assertEquals(123, l.getFlags());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testGetFlags_Deprecation() {
        // Test if deprecated bit was added/remove automatically based on the provided deprecation
        // time
        LinkAddress l = new LinkAddress(V6_ADDRESS, 64, 0, RT_SCOPE_HOST,
                1L, LinkAddress.LIFETIME_PERMANENT);
        // Check if the flag is added automatically.
        assertTrue((l.getFlags() & IFA_F_DEPRECATED) != 0);

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_DEPRECATED, RT_SCOPE_HOST,
                SystemClock.elapsedRealtime() + 100000L, LinkAddress.LIFETIME_PERMANENT);
        // Check if the flag is removed automatically.
        assertTrue((l.getFlags() & IFA_F_DEPRECATED) == 0);

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_DEPRECATED, RT_SCOPE_HOST,
                LinkAddress.LIFETIME_PERMANENT, LinkAddress.LIFETIME_PERMANENT);
        // Check if the permanent flag is added.
        assertTrue((l.getFlags() & IFA_F_PERMANENT) != 0);

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_PERMANENT, RT_SCOPE_HOST,
                1000L, SystemClock.elapsedRealtime() + 100000L);
        // Check if the permanent flag is removed
        assertTrue((l.getFlags() & IFA_F_PERMANENT) == 0);
    }

    private void assertGlobalPreferred(LinkAddress l, String msg) {
        assertTrue(msg, l.isGlobalPreferred());
    }

    private void assertNotGlobalPreferred(LinkAddress l, String msg) {
        assertFalse(msg, l.isGlobalPreferred());
    }

    @Test
    public void testIsGlobalPreferred() {
        LinkAddress l;

        l = new LinkAddress(V4_ADDRESS, 32, 0, RT_SCOPE_UNIVERSE);
        assertGlobalPreferred(l, "v4,global,noflags");

        l = new LinkAddress("10.10.1.7/23", 0, RT_SCOPE_UNIVERSE);
        assertGlobalPreferred(l, "v4-rfc1918,global,noflags");

        l = new LinkAddress("10.10.1.7/23", 0, RT_SCOPE_SITE);
        assertNotGlobalPreferred(l, "v4-rfc1918,site-local,noflags");

        l = new LinkAddress("127.0.0.7/8", 0, RT_SCOPE_HOST);
        assertNotGlobalPreferred(l, "v4-localhost,node-local,noflags");

        l = new LinkAddress(V6_ADDRESS, 64, 0, RT_SCOPE_UNIVERSE);
        assertGlobalPreferred(l, "v6,global,noflags");

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_PERMANENT, RT_SCOPE_UNIVERSE);
        assertGlobalPreferred(l, "v6,global,permanent");

        // IPv6 ULAs are not acceptable "global preferred" addresses.
        l = new LinkAddress("fc12::1/64", 0, RT_SCOPE_UNIVERSE);
        assertNotGlobalPreferred(l, "v6,ula1,noflags");

        l = new LinkAddress("fd34::1/64", 0, RT_SCOPE_UNIVERSE);
        assertNotGlobalPreferred(l, "v6,ula2,noflags");

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_TEMPORARY, RT_SCOPE_UNIVERSE);
        assertGlobalPreferred(l, "v6,global,tempaddr");

        l = new LinkAddress(V6_ADDRESS, 64, (IFA_F_TEMPORARY|IFA_F_DADFAILED),
                            RT_SCOPE_UNIVERSE);
        assertNotGlobalPreferred(l, "v6,global,tempaddr+dadfailed");

        l = new LinkAddress(V6_ADDRESS, 64, (IFA_F_TEMPORARY|IFA_F_DEPRECATED),
                            RT_SCOPE_UNIVERSE);
        assertNotGlobalPreferred(l, "v6,global,tempaddr+deprecated");

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_TEMPORARY, RT_SCOPE_SITE);
        assertNotGlobalPreferred(l, "v6,site-local,tempaddr");

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_TEMPORARY, RT_SCOPE_LINK);
        assertNotGlobalPreferred(l, "v6,link-local,tempaddr");

        l = new LinkAddress(V6_ADDRESS, 64, IFA_F_TEMPORARY, RT_SCOPE_HOST);
        assertNotGlobalPreferred(l, "v6,node-local,tempaddr");

        l = new LinkAddress("::1/128", IFA_F_PERMANENT, RT_SCOPE_HOST);
        assertNotGlobalPreferred(l, "v6-localhost,node-local,permanent");

        l = new LinkAddress(V6_ADDRESS, 64, (IFA_F_TEMPORARY|IFA_F_TENTATIVE),
                            RT_SCOPE_UNIVERSE);
        assertNotGlobalPreferred(l, "v6,global,tempaddr+tentative");

        l = new LinkAddress(V6_ADDRESS, 64,
                            (IFA_F_TEMPORARY|IFA_F_TENTATIVE|IFA_F_OPTIMISTIC),
                            RT_SCOPE_UNIVERSE);
        assertGlobalPreferred(l, "v6,global,tempaddr+optimistic");
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testIsGlobalPreferred_DeprecatedInFuture() {
        final LinkAddress l = new LinkAddress(V6_ADDRESS, 64, IFA_F_DEPRECATED,
                RT_SCOPE_UNIVERSE, SystemClock.elapsedRealtime() + 100000,
                SystemClock.elapsedRealtime() + 200000);
        // Although the deprecated bit is set, but the deprecation time is in the future, test
        // if the flag is removed automatically.
        assertGlobalPreferred(l, "v6,global,tempaddr+deprecated in the future");
    }
}
