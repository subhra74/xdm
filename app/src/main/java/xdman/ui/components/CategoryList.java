package xdman.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.AbstractListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;

import xdman.ui.res.StringResource;
import xdman.util.XDMUtils;

public class CategoryList {
	private CategoryListModel model;
	private JList<CategoryItem> list;
	private ActionListener callback;

	public CategoryList(Font iconFont) {
		model = new CategoryListModel();
		list = new JList<>(model);
		list.setCellRenderer(new CategoryListRenderer(iconFont));
		list.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) {
				return;
			}
			var index = list.getSelectedIndex();
			var value = model.getElementAt(index);
			if (callback != null) {
				callback.actionPerformed(new ActionEvent(this, 0, value.name));
			}
		});
	}

	public Component getListComponent() {
		var jsp = new JScrollPane(list);
		jsp.setBorder(new EmptyBorder(0, 0, 0, 0));
		jsp.setPreferredSize(new Dimension(XDMUtils.getScaledInt(200), 200));
		return jsp;
	}

	public void addCategory(String name, String icon, boolean topLevel) {
		var cat = new CategoryItem();
		cat.name = name;
		cat.topLevel = topLevel;
		cat.icon = icon;
		model.addCategory(cat);
	}

	public void setActionListener(ActionListener a) {
		this.callback = a;
	}

	public void selectFirstIndex() {
		this.list.setSelectedIndex(0);
	}

	class CategoryItem {
		public boolean topLevel;
		public String name;
		public String icon;
	}

	class CategoryListModel extends AbstractListModel<CategoryItem> {
		private ArrayList<CategoryItem> categoryList = new ArrayList<>();

		@Override
		public int getSize() {
			return categoryList.size();
		}

		@Override
		public CategoryItem getElementAt(int index) {
			return categoryList.get(index);
		}

		public void addCategory(CategoryItem cat) {
			var index = getSize();
			categoryList.add(cat);
			fireIntervalAdded(cat, index, index);
		}
	}

	class CategoryListRenderer extends JPanel implements ListCellRenderer<CategoryItem> {
		private JLabel lblIcon, lblText;
		private Border b1, b2;

		public CategoryListRenderer(Font iconFont) {
			super(new BorderLayout(XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(10)));
			b1 = new EmptyBorder(new Insets(XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(20), XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(10)));
			b2 = new EmptyBorder(new Insets(XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(35), XDMUtils.getScaledInt(10), XDMUtils.getScaledInt(10)));
			lblIcon = new JLabel();
			lblText = new JLabel();
			add(lblIcon, BorderLayout.WEST);
			add(lblText, BorderLayout.CENTER);
			setBorder(b1);
			lblIcon.setFont(iconFont);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends CategoryItem> list, CategoryItem value, int index,
				boolean isSelected, boolean cellHasFocus) {
			setBorder(value.topLevel ? b1 : b2);
			var icon = value.icon;
			var text = StringResource.get(value.name);
			lblIcon.setText(icon);
			lblText.setText(text);
			if (isSelected) {
				setBackground(list.getSelectionBackground());
				lblIcon.setForeground(list.getSelectionForeground());
				lblText.setForeground(list.getSelectionForeground());
			} else {
				setBackground(list.getBackground());
				lblIcon.setForeground(list.getForeground());
				lblText.setForeground(list.getForeground());
			}
			return this;
		}
	}
}
