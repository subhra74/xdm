package xdman.ui.components;

import java.io.File;

import javax.swing.JFileChooser;

public class XDMFileChooser {
	static JFileChooser jfc;

	public static JFileChooser getFileChooser(int mode, File file) {
		if (jfc == null) {
			jfc = new JFileChooser();
		}
		jfc.setFileSelectionMode(mode);
		if (mode == JFileChooser.DIRECTORIES_ONLY) {
			if (file != null) {
				jfc.setCurrentDirectory(file);
			}
		} else {
			jfc.setSelectedFile(file);
		}
		return jfc;
	}
}
