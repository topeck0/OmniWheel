using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace OmniWheelPC.Network;

/// <summary>
/// Enumerates the machine's usable IPv4 addresses, ordered with the most likely
/// "primary" interface (the one carrying the default route) first, then any
/// others. Lets a user with two routers or multiple adapters see every address
/// their phone might reach, with the main one first.
/// </summary>
public static class LocalAddresses
{
    public static List<string> GetIpv4Preferred()
    {
        var primary = new List<string>();
        var others = new List<string>();
        var seen = new HashSet<string>();

        try
        {
            var defRouteNics = GetDefaultRouteInterfaceIds();

            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                if (ni.NetworkInterfaceType is NetworkInterfaceType.Tunnel or
                    NetworkInterfaceType.Ppp or
                    NetworkInterfaceType.GenericModem) continue;

                var ipv4 = ni.GetIPProperties().UnicastAddresses
                    .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address))
                    .Select(a => a.Address.ToString())
                    .Where(IsUsable)
                    .ToList();

                bool isDefault = defRouteNics.Contains(ni.Id);
                foreach (var ip in ipv4)
                {
                    if (!seen.Add(ip)) continue;
                    if (isDefault) primary.Add(ip);
                    else others.Add(ip);
                }
            }
        }
        catch
        {
            // Never let enumeration break the UI.
        }

        var result = new List<string>();
        result.AddRange(primary);
        result.AddRange(others);
        return result;
    }

    /// <summary>Filter out link-local, zero, and loopback ranges.</summary>
    private static bool IsUsable(string ip)
    {
        if (ip.StartsWith("169.254.", StringComparison.Ordinal)) return false;
        if (ip.StartsWith("0.", StringComparison.Ordinal)) return false;
        if (ip.StartsWith("255.", StringComparison.Ordinal)) return false;
        return true;
    }

    /// <summary>
    /// Best-effort: adapter Ids that have a default gateway on their IPv4
    /// properties (the classic LAN/router interface).
    /// </summary>
    private static HashSet<string> GetDefaultRouteInterfaceIds()
    {
        var ids = new HashSet<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                var props = ni.GetIPProperties();
                if (props.GatewayAddresses.Count > 0 &&
                    props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork))
                {
                    ids.Add(ni.Id);
                }
            }
        }
        catch { }
        return ids;
    }
}