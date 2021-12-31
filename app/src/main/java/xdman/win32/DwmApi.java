package xdman.win32;

import java.awt.Window;
import java.util.Arrays;
import java.util.List;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;

public interface DwmApi extends Library {

	public final static DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

	public static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

	int DwmSetWindowAttribute(HWND hwnd, int dwAttribute, PointerType pvAttribute, int cbAttribute);

	public static void SetDarkTitleBar(final Window w) {
		final HWND hwnd = new HWND();
		hwnd.setPointer(Native.getComponentPointer(w));
		System.out.println(Native.getComponentPointer(w));
		System.out
				.println(INSTANCE.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(1), 4));
	}

}
