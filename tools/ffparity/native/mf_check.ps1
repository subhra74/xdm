# What Windows' own player stack (Media Foundation: Films & TV, Media Player, Edge) makes of an MP4:
# the MP4 source and decoder MFTs read every video frame (decoded to NV12) and audio frame, and one JSON
# object is printed: whether reading reached the end or stopped with an error, frames, held frames
# (presentation gaps over 1.5 frames: stutter), duplicate/backward times, format changes, first times.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File mf_check.ps1 C:\path\file.mp4
# Windows PowerShell 5.1 compiles the C# below itself: no SDK needed.
param([Parameter(Mandatory = $true)][string]$Path)

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

// IMFAttributes has 30 methods; IMFMediaType adds 5. Only SetGUID is called; the other slots keep the vtable order.
[ComImport, Guid("44ae0fa8-ea31-4109-8d2e-4cae4997c555"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMFMediaType {
    void GetItem(); void GetItemType(); void CompareItem(); void Compare(); void GetUINT32(); void GetUINT64();
    void GetDouble(); void GetGUID(); void GetStringLength(); void GetString(); void GetAllocatedString();
    void GetBlobSize(); void GetBlob(); void GetAllocatedBlob(); void GetUnknown(); void SetItem(); void DeleteItem();
    void DeleteAllItems(); void SetUINT32(); void SetUINT64(); void SetDouble();
    [PreserveSig] int SetGUID([In] ref Guid key, [In] ref Guid value);
    void SetString(); void SetBlob(); void SetUnknown(); void LockStore(); void UnlockStore(); void GetCount();
    void GetItemByIndex(); void CopyAllItems();
    void GetMajorType(); void IsCompressedFormat(); void IsEqual(); void GetRepresentation(); void FreeRepresentation();
}

[ComImport, Guid("70ae66f2-c809-4e4f-8915-bdcb406b7993"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IMFSourceReader {
    void GetStreamSelection();
    [PreserveSig] int SetStreamSelection(uint stream, bool selected);
    void GetNativeMediaType(); void GetCurrentMediaType();
    [PreserveSig] int SetCurrentMediaType(uint stream, IntPtr reserved, IMFMediaType type);
    void SetCurrentPosition();
    [PreserveSig] int ReadSample(uint stream, uint control, out uint actualStream, out uint flags, out long timestamp, out IntPtr sample);
    void Flush(); void GetServiceForStream(); void GetPresentationAttribute();
}

public static class MfCheck {
    [DllImport("mfplat.dll")] static extern int MFStartup(uint version, uint flags);
    [DllImport("mfplat.dll")] static extern int MFCreateMediaType(out IMFMediaType type);
    [DllImport("mfreadwrite.dll")] static extern int MFCreateSourceReaderFromURL([MarshalAs(UnmanagedType.LPWStr)] string url, IntPtr attributes, out IMFSourceReader reader);

    const uint VIDEO = 0xFFFFFFFC, AUDIO = 0xFFFFFFFD, ALL = 0xFFFFFFFE;
    const uint ERROR = 0x1, END = 0x2, TYPE_CHANGED = 0x20 | 0x10, TICK = 0x100;
    static Guid MT_MAJOR = new Guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f"), MT_SUBTYPE = new Guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    static Guid VIDEO_TYPE = new Guid("73646976-0000-0010-8000-00AA00389B71"), NV12 = new Guid("3231564E-0000-0010-8000-00AA00389B71");
    static Guid AUDIO_TYPE = new Guid("73647561-0000-0010-8000-00AA00389B71"), PCM = new Guid("00000001-0000-0010-8000-00AA00389B71");

    static string Read(IMFSourceReader r, uint stream, List<double> times, ref int changes, ref int ticks) {
        while (true) {
            uint actual, flags; long ts; IntPtr sample;
            int hr = r.ReadSample(stream, 0, out actual, out flags, out ts, out sample);
            if (sample != IntPtr.Zero) { times.Add(ts / 1e7); Marshal.Release(sample); }
            if (hr < 0) return "ReadSample 0x" + hr.ToString("X8");
            if ((flags & ERROR) != 0) return "stream error";
            if ((flags & TYPE_CHANGED) != 0) changes++;
            if ((flags & TICK) != 0) ticks++;
            if ((flags & END) != 0) return null;
        }
    }

    static string Stats(string name, List<double> t, string error, int changes, int ticks) {
        int held = 0, dup = 0; double longest = 0, typical = 0;
        var steps = new List<double>();
        for (int i = 1; i < t.Count; i++) { double s = t[i] - t[i - 1]; if (s <= 0) dup++; else steps.Add(s); if (s > longest) longest = s; }
        if (steps.Count > 0) { steps.Sort(); typical = steps[steps.Count / 2]; foreach (var s in steps) if (s > typical * 1.5) held++; }
        return string.Format(System.Globalization.CultureInfo.InvariantCulture,
            "\"{0}\":{{\"frames\":{1},\"firstTime\":{2},\"lastTime\":{3},\"heldFrames\":{4},\"longestGapMs\":{5:0.0},\"duplicateOrBackward\":{6},\"formatChanges\":{7},\"gapTicks\":{8},\"error\":{9}}}",
            name, t.Count, t.Count > 0 ? t[0] : -1, t.Count > 0 ? t[t.Count - 1] : -1, held, longest * 1000, dup, changes, ticks,
            error == null ? "null" : "\"" + error + "\"");
    }

    static IMFMediaType Type(Guid major, Guid sub) {
        IMFMediaType t; MFCreateMediaType(out t); t.SetGUID(ref MT_MAJOR, ref major); t.SetGUID(ref MT_SUBTYPE, ref sub); return t;
    }

    public static string Run(string path) {
        MFStartup(0x20070, 0);
        var parts = new List<string>();
        foreach (var kind in new[] { "video", "audio" }) {
            IMFSourceReader r;
            int hr = MFCreateSourceReaderFromURL(path, IntPtr.Zero, out r);
            if (hr < 0) return "{\"opens\":false,\"error\":\"MFCreateSourceReaderFromURL 0x" + hr.ToString("X8") + "\"}";
            uint stream = kind == "video" ? VIDEO : AUDIO;
            r.SetStreamSelection(ALL, false);
            if (r.SetStreamSelection(stream, true) < 0) continue; // no such stream
            hr = kind == "video" ? r.SetCurrentMediaType(VIDEO, IntPtr.Zero, Type(VIDEO_TYPE, NV12))
                                 : r.SetCurrentMediaType(AUDIO, IntPtr.Zero, Type(AUDIO_TYPE, PCM));
            if (hr < 0) { parts.Add("\"" + kind + "\":{\"frames\":0,\"error\":\"no decoder (SetCurrentMediaType 0x" + hr.ToString("X8") + ")\"}"); continue; }
            var times = new List<double>(); int changes = 0, ticks = 0;
            string err = Read(r, stream, times, ref changes, ref ticks);
            parts.Add(Stats(kind, times, err, changes, ticks));
            Marshal.ReleaseComObject(r);
        }
        return "{\"opens\":true," + string.Join(",", parts) + "}";
    }
}
'@

[MfCheck]::Run((Resolve-Path $Path).Path)
