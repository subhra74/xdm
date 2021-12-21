using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.Drawing.Text;
using System.IO;
using System.Linq;
using System.Reflection;

using System.Windows.Forms;
using XDM.Core.Lib.Util;
using XDMApp;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    partial class AboutBox : Form
    {
        private PrivateFontCollection fontInstance;
        public AboutBox()
        {
            InitializeComponent();
            this.Text = String.Format("About {0}", "XDM");
            this.labelProductName.Text = "Xtreme Download Manager - 8.0.0 beta";
            this.labelVersion.Text = String.Format("CLR version {0}", Environment.Version);
            this.labelCopyright.Text = "Copyright (C) 2021 Subhra Das Gupta";
            this.linkLabel1.Text = "https://xtremedownloadmanager.com";
            this.textBoxDescription.Text = AssemblyDescription;
            label1.Padding = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(10), LogicalToDeviceUnits(10), 0);
            label2.Padding = new Padding(LogicalToDeviceUnits(10), LogicalToDeviceUnits(0), LogicalToDeviceUnits(10), LogicalToDeviceUnits(10));

            var margin = new Padding(LogicalToDeviceUnits(7), 0, LogicalToDeviceUnits(3), 0);
            labelProductName.Margin = labelVersion.Margin = labelCopyright.Margin =
                linkLabel1.Margin = textBoxDescription.Margin = margin;
            okButton.Margin = new Padding(LogicalToDeviceUnits(3));
            textBoxDescription.Text = File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "gpl-3.0.txt"));
        }

        #region Assembly Attribute Accessors

        public string AssemblyTitle
        {
            get
            {
                object[] attributes = Assembly.GetExecutingAssembly().GetCustomAttributes(typeof(AssemblyTitleAttribute), false);
                if (attributes.Length > 0)
                {
                    AssemblyTitleAttribute titleAttribute = (AssemblyTitleAttribute)attributes[0];
                    if (titleAttribute.Title != "")
                    {
                        return titleAttribute.Title;
                    }
                }
                return System.IO.Path.GetFileNameWithoutExtension(Assembly.GetExecutingAssembly().CodeBase);
            }
        }

        public string AssemblyVersion
        {
            get
            {
                return Assembly.GetExecutingAssembly().GetName().Version.ToString();
            }
        }

        public string AssemblyDescription
        {
            get
            {
                object[] attributes = Assembly.GetExecutingAssembly().GetCustomAttributes(typeof(AssemblyDescriptionAttribute), false);
                if (attributes.Length == 0)
                {
                    return "";
                }
                return ((AssemblyDescriptionAttribute)attributes[0]).Description;
            }
        }

        public string AssemblyProduct
        {
            get
            {
                object[] attributes = Assembly.GetExecutingAssembly().GetCustomAttributes(typeof(AssemblyProductAttribute), false);
                if (attributes.Length == 0)
                {
                    return "";
                }
                return ((AssemblyProductAttribute)attributes[0]).Product;
            }
        }

        public string AssemblyCopyright
        {
            get
            {
                object[] attributes = Assembly.GetExecutingAssembly().GetCustomAttributes(typeof(AssemblyCopyrightAttribute), false);
                if (attributes.Length == 0)
                {
                    return "";
                }
                return ((AssemblyCopyrightAttribute)attributes[0]).Copyright;
            }
        }

        public string AssemblyCompany
        {
            get
            {
                object[] attributes = Assembly.GetExecutingAssembly().GetCustomAttributes(typeof(AssemblyCompanyAttribute), false);
                if (attributes.Length == 0)
                {
                    return "";
                }
                return ((AssemblyCompanyAttribute)attributes[0]).Company;
            }
        }
        #endregion

        private void linkLabel1_LinkClicked(object sender, LinkLabelLinkClickedEventArgs e)
        {
            Helpers.OpenBrowser("https://xtremedownloadmanager.com/");
        }
    }
}
