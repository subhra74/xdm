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
using XDM.GtkUI.Dialogs.AdvancedDownload;
using UI = Gtk.Builder.ObjectAttribute;

namespace XDM.GtkUI.Dialogs.NewVideoDownload
{
    public class NewVideoDownloadWindow : Window, INewVideoDownloadDialog
    {
        [UI] private Entry TxtFile;
        [UI] private ComboBox CmbLocation;
        [UI] private Label lblFileSize;
        [UI] private Label lblFile;
        [UI] private Label lblSaveIn;
        [UI] private MenuButton btnDownloadLater;
        [UI] private Button btnDownloadNow;
        [UI] private Button btnMore;
        [UI] private Gtk.Menu menu1;

        private WindowGroup windowGroup;

        private Gtk.MenuItem dontAddToQueueMenuItem;
        private Gtk.MenuItem queueAndSchedulerMenuItem;

        private int previousIndex = 0;
        public AuthenticationInfo? Authentication { get => authentication; set => authentication = value; }
        public ProxyInfo? Proxy { get => proxy; set => proxy = value; }
        public int SpeedLimit { get => speedLimit; set => speedLimit = value; }
        public bool EnableSpeedLimit { get => enableSpeedLimit; set => enableSpeedLimit = value; }

        private AuthenticationInfo? authentication;
        private ProxyInfo? proxy = Config.Instance.Proxy;
        private int speedLimit = Config.Instance.DefaltDownloadSpeed;
        private bool enableSpeedLimit = Config.Instance.EnableSpeedLimit;

        public event EventHandler DownloadClicked;
        public event EventHandler<DownloadLaterEventArgs> DownloadLaterClicked;
        public event EventHandler CancelClicked, DestroyEvent, QueueSchedulerClicked, Mp3CheckChanged;
        public event EventHandler<FileBrowsedEventArgs> DropdownSelectionChangedEvent;
        public event EventHandler<FileBrowsedEventArgs> FileBrowsedEvent;

        private ListStore dropdownItems;

        public static NewVideoDownloadWindow CreateFromGladeFile()
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "new-video-download-window.glade"));
            return new NewVideoDownloadWindow(builder);
        }

        private NewVideoDownloadWindow(Builder builder) : base(builder.GetRawOwnedObject("window"))
        {
            builder.Autoconnect(this);
            SetDefaultSize(500, 300);
            KeepAbove = true;
            Title = TextResource.GetText("ND_TITLE");
            SetPosition(WindowPosition.Center);

            windowGroup = new WindowGroup();
            windowGroup.AddWindow(this);

            dropdownItems = new ListStore(typeof(string));

            lblFile.Text = TextResource.GetText("ND_FILE");
            lblSaveIn.Text = TextResource.GetText("LBL_SAVE_IN");
            btnDownloadNow.Label = TextResource.GetText("ND_DOWNLOAD_NOW");
            btnDownloadLater.Label = TextResource.GetText("ND_DOWNLOAD_LATER");
            btnMore.Label = TextResource.GetText("ND_MORE");

            CmbLocation.Changed += CmbLocation_Changed;
            this.Destroyed += Window_Closed;
            btnDownloadNow.Clicked += btnDownload_Click;
            //btnDownloadLater.Clicked += btnDownloadLater_Click;
            btnMore.Clicked += btnAdvanced_Click;

            dropdownItems = GtkHelper.PopulateComboBox(CmbLocation);

            //CmbLocation.Model = dropdownItems;
            //var cmbRenderer = new CellRendererText();
            //CmbLocation.PackStart(cmbRenderer, true);
            //CmbLocation.AddAttribute(cmbRenderer, "text", 0);

            PrepareMenu();

            this.ShowAll();

            GtkHelper.AttachSafeDispose(this);
        }

        private void CmbLocation_Changed(object? sender, EventArgs e)
        {
            if (CmbLocation.Active == 1)
            {
                var folder = GtkHelper.SelectFolder(this);
                if (!string.IsNullOrEmpty(folder))
                {
                    this.FileBrowsedEvent?.Invoke(this, new FileBrowsedEventArgs(folder));
                }
                else
                {
                    CmbLocation.Active = previousIndex;
                }
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

        private void btnDownload_Click(object? sender, EventArgs e)
        {
            DownloadClicked?.Invoke(sender, e);
        }

        private void btnAdvanced_Click(object? sender, EventArgs e)
        {
            var dlg = new AdvancedDownloadDialog(AdvancedDownloadDialog.LoadBuilder(), this, this.windowGroup)
            {
                Authentication = Authentication,
                Proxy = Proxy,
                EnableSpeedLimit = EnableSpeedLimit,
                SpeedLimit = SpeedLimit,
            };
            dlg.Run();
            if (dlg.Result)
            {
                Authentication = dlg.Authentication;
                Proxy = dlg.Proxy;
                EnableSpeedLimit = dlg.EnableSpeedLimit;
                SpeedLimit = dlg.SpeedLimit;
            }
            dlg.Destroy();
        }

        private void DontAddToQueueMenuItem_Click(object? sender, EventArgs e)
        {
            this.DownloadLaterClicked?.Invoke(this, new DownloadLaterEventArgs(string.Empty));
        }

        private void QueueAndSchedulerMenuItem_Click(object? sender, EventArgs e)
        {
            this.QueueSchedulerClicked?.Invoke(this, EventArgs.Empty);
        }

        private void PrepareMenu()
        {
            dontAddToQueueMenuItem = new Gtk.MenuItem(TextResource.GetText("LBL_QUEUE_OPT3"));
            queueAndSchedulerMenuItem = new Gtk.MenuItem(TextResource.GetText("DESC_Q_TITLE"));

            dontAddToQueueMenuItem.Activated += DontAddToQueueMenuItem_Click;
            queueAndSchedulerMenuItem.Activated += QueueAndSchedulerMenuItem_Click;

            DownloadLaterMenuHelper.PopulateMenuAndAttachEvents(
                args => DownloadLaterClicked?.Invoke(this, args),
                menu1,
                dontAddToQueueMenuItem,
                queueAndSchedulerMenuItem,
                this);
        }

        public string SelectedFileName { get => TxtFile.Text; set => TxtFile.Text = value; }

        public string FileSize { get => lblFileSize.Text; set => lblFileSize.Text = value; }

        public int SeletedFolderIndex
        {
            get => CmbLocation.Active;
            set
            {
                CmbLocation.Active = value;
                previousIndex = value;
            }
        }

        public bool ShowMp3Checkbox
        {
            get; set;
            //get => ChkMp3.Visibility == Visibility.Visible;
            //set => ChkMp3.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        }

        public bool IsMp3CheckboxChecked { get; set; }/*{ get => ChkMp3.IsChecked ?? false; set => ChkMp3.IsChecked = value; }*/

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
    }
}
