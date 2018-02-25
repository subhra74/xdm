package xdman.ui.laf;

import java.awt.Component;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSpinnerUI;

import xdman.ui.components.CustomButton;
import xdman.ui.res.ColorResource;
import xdman.ui.res.ImageResource;

public class XDMSpinnerUI extends BasicSpinnerUI {
	public static ComponentUI createUI(JComponent c) {
		return new XDMSpinnerUI();
	}

	protected Component createNextButton() {
		CustomButton btn = new CustomButton();
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setContentAreaFilled(false);
		btn.setHorizontalAlignment(JButton.CENTER);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setBorderPainted(false);
		btn.setIcon(ImageResource.get("up.png"));
		btn.setName("Spinner.nextButton");
		installNextButtonListeners(btn);
		return btn;
	}

	protected Component createPreviousButton() {
		CustomButton btn = new CustomButton();
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setContentAreaFilled(false);
		btn.setHorizontalAlignment(JButton.CENTER);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setBorderPainted(false);
		btn.setIcon(ImageResource.get("down.png"));
		btn.setName("Spinner.previousButton");
		installPreviousButtonListeners(btn);
		return btn;
	}

}
