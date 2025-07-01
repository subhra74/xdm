package xdm.ui.laf;

import java.awt.Component;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import xdm.ui.components.CustomButton;
import xdm.ui.res.ColorResource;
import xdm.ui.res.ImageResource;

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
		btn.setIcon(ImageResource.getIcon("up.png",10,10));
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
		btn.setIcon(ImageResource.getIcon("down.png",10,10));
		btn.setName("Spinner.previousButton");
		installPreviousButtonListeners(btn);
		return btn;
	}

}
