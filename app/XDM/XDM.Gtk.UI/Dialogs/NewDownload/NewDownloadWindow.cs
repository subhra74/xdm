using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

using Gtk;
using System.IO;
using GLib;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.GtkUI.Utils;
using Translations;

namespace XDM.GtkUI.Dialogs.NewDownload
{
    public class NewDownloadWindow : Window, INewDownloadDialogSkeleton
    {
        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;
        private int previousIndex = 0;

        private Entry TxtUrl, TxtFile;
        private ComboBox CmbLocation;
        private ListStore dropdownItems;
        private Label lblFileSize, lblAddress, lblFile, lblSaveIn;
        private LinkButton lblIgnoreLabel;
        private Button btnDownloadLater, btnDownloadNow, btnMore;

        public NewDownloadWindow() : base(TextResource.GetText("ND_TITLE"))
        {
            SetDefaultSize(550, 300);
            SetPosition(WindowPosition.Center);

            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "new-download-window.glade"));
            var tempWindow = (Window)builder.GetObject("window");
            var mainBox = (Widget)builder.GetObject("mainBox");
            tempWindow.Remove(mainBox);
            mainBox.ShowAll();
            this.Add(mainBox);

            dropdownItems = new ListStore(typeof(string));

            TxtUrl = (Entry)builder.GetObject("txtUrl");
            TxtFile = (Entry)builder.GetObject("txtFile");
            CmbLocation = (ComboBox)builder.GetObject("cmdFolder");
            lblFileSize = (Label)builder.GetObject("lblFileSize");
            lblIgnoreLabel = (LinkButton)builder.GetObject("lblIgnoreLabel");
            btnDownloadLater = (Button)builder.GetObject("btnDownloadLater");
            btnDownloadNow = (Button)builder.GetObject("btnDownloadNow");
            btnMore = (Button)builder.GetObject("btnMore");
            lblAddress = (Label)builder.GetObject("lblAddress");
            lblFile = (Label)builder.GetObject("lblFile");
            lblSaveIn = (Label)builder.GetObject("lblSaveIn");

            lblAddress.Text = TextResource.GetText("ND_ADDRESS");
            lblFile.Text = TextResource.GetText("ND_FILE");
            lblSaveIn.Text = TextResource.GetText("LBL_SAVE_IN");
            btnDownloadNow.Label = TextResource.GetText("ND_DOWNLOAD_NOW");
            btnDownloadLater.Label = TextResource.GetText("ND_DOWNLOAD_LATER");
            btnMore.Label = TextResource.GetText("ND_MORE");
            lblIgnoreLabel.Label = TextResource.GetText("ND_IGNORE_URL");

            CmbLocation.Changed += CmbLocation_Changed;
            this.Destroyed += Window_Closed;
            TxtUrl.Changed += TxtUrl_TextChanged;
            btnDownloadNow.Clicked += btnDownload_Click;
            btnDownloadLater.Clicked += btnDownloadLater_Click;
            btnMore.Clicked += btnAdvanced_Click;
            lblIgnoreLabel.Clicked += TextBlock_MouseDown;

            CmbLocation.Model = dropdownItems;
            var cmbRenderer = new CellRendererText();
            CmbLocation.PackStart(cmbRenderer, true);
            CmbLocation.AddAttribute(cmbRenderer, "text", 0);

            this.ShowAll();
        }

        public bool IsEmpty { get => !TxtUrl.IsEditable; set => TxtUrl.IsEditable = !value; }
        public string Url { get => TxtUrl.Text; set => TxtUrl.Text = value; }
        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }
        public string SelectedFileName { get => TxtFile.Text; set => TxtFile.Text = value; }
        public int SeletedFolderIndex
        {
            get => CmbLocation.Active;
            set
            {
                CmbLocation.Active = value;
                previousIndex = value;
            }
        }

        public event EventHandler? DownloadClicked;
        public event EventHandler? CancelClicked;
        public event EventHandler? DestroyEvent;
        public event EventHandler? BlockHostEvent;
        public event EventHandler? UrlChangedEvent;
        public event EventHandler? UrlBlockedEvent;
        public event EventHandler? QueueSchedulerClicked;
        public event EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked;
        public event EventHandler<FileBrowsedEventArgs>? FileBrowsedEvent;
        public event EventHandler<FileBrowsedEventArgs>? DropdownSelectionChangedEvent;

        public void DisposeWindow()
        {
            this.Close();
        }

        public void Invoke(System.Action callback)
        {
            Application.Invoke(delegate { callback.Invoke(); });
        }

        public void SetFileSizeText(string text)
        {
            this.lblFileSize.Text = text;
        }

        public void SetFolderValues(string[] values)
        {
            dropdownItems.Clear();
            previousIndex = 0;
            foreach (var item in values)
            {
                dropdownItems.AppendValues(item);
            }
        }

        public void ShowMessageBox(string message)
        {
            GtkHelper.ShowMessageBox(this, message);
        }

        public void ShowWindow()
        {
            this.Show();
        }

        private void CmbLocation_Changed(object? sender, EventArgs e)
        {
            if (CmbLocation.Active == 1)
            {
                var fc = new FileChooserDialog("XDM", this, FileChooserAction.SelectFolder);
                fc.AddButton(Gtk.Stock.Cancel, ResponseType.Cancel);
                fc.AddButton(Gtk.Stock.Save, ResponseType.Accept);
                if (fc.Run() == (int)Gtk.ResponseType.Accept)
                {
                    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fc.Filename));
                }
                //using var fb = new System.Windows.Forms.FolderBrowserDialog();
                //if (fb.ShowDialog(new WinformsWindow(this)) == System.Windows.Forms.DialogResult.OK)
                //{
                //    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fb.SelectedPath));
                //}
                ////var fc = new SaveFileDialog();
                ////fc.Filter = "All files (*.*)|*.*";
                ////fc.FileName = TxtFile.Text;
                ////var ret = fc.ShowDialog(this);
                ////if (ret.HasValue && ret.Value)
                ////{
                ////    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(fc.FileName));
                ////}
                else
                {
                    CmbLocation.Active = previousIndex;
                }
                fc.Destroy();
            }
            else
            {
                previousIndex = CmbLocation.Active;
                this.DropdownSelectionChangedEvent?.Invoke(this,
                    new FileBrowsedEventArgs(GtkHelper.GetComboBoxSelectedItem<string>(CmbLocation)));
            }
        }

        private void Window_Closed(object? sender, EventArgs e)
        {
            this.DestroyEvent?.Invoke(this, EventArgs.Empty);
        }

        private void TxtUrl_TextChanged(object? sender, EventArgs e)
        {
            UrlChangedEvent?.Invoke(sender, e);
        }

        private void btnDownload_Click(object? sender, EventArgs e)
        {
            DownloadClicked?.Invoke(sender, e);
        }

        private void btnDownloadLater_Click(object? sender, EventArgs e)
        {
            ShowQueuesContextMenu();
        }

        private void btnAdvanced_Click(object? sender, EventArgs e)
        {
            //var dlg = new AdvancedDownloadOptionDialog
            //{
            //    Authentication = Authentication,
            //    Proxy = Proxy,
            //    EnableSpeedLimit = EnableSpeedLimit,
            //    SpeedLimit = SpeedLimit,
            //    Owner = this
            //};
            //var ret = dlg.ShowDialog(this);

            //if (ret.HasValue && ret.Value)
            //{
            //    Authentication = dlg.Authentication;
            //    Proxy = dlg.Proxy;
            //    EnableSpeedLimit = dlg.EnableSpeedLimit;
            //    SpeedLimit = dlg.SpeedLimit;
            //}
        }

        private void TextBlock_MouseDown(object? sender, EventArgs e)
        {
            UrlBlockedEvent?.Invoke(sender, EventArgs.Empty);
        }

        private void ShowQueuesContextMenu()
        {
            //DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(DownloadLaterClicked, btnDownloadLater, this);
        }

        private void DontAddToQueueMenuItem_Click(object sender, EventArgs e)
        {
            this.DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(string.Empty));
        }

        private void QueueAndSchedulerMenuItem_Click(object sender, EventArgs e)
        {
            this.QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

    }
}
