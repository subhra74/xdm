using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using Translations;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI.Dialogs.Updater
{
    /// <summary>
    /// Interaction logic for UpdaterWindow.xaml
    /// </summary>
    public partial class UpdaterWindow : Window, IUpdaterUI
    {
        private IAppController AppUI;
        private Action actClose;
        private bool active = false;
        public UpdaterWindow(IAppController AppUI)
        {
            InitializeComponent();
            this.AppUI = AppUI;
            this.Loaded += (_, _) => Load?.Invoke(this, EventArgs.Empty);
            actClose = new Action(() => Close());
            Closing += UpdaterWindow_Closing;
        }

        private void UpdaterWindow_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            if (active)
            {
                Cancelled?.Invoke(sender, e);
            }
        }

        public event EventHandler? Cancelled;
        public event EventHandler? Finished;
        public event EventHandler? Load;

        public void DownloadCancelled(object? sender, EventArgs e)
        {
            Dispatcher.Invoke(actClose);
        }

        public void DownloadFailed(object? sender, DownloadFailedEventArgs e)
        {
            active = false;
            Dispatcher.Invoke(new Action(() =>
            {
                MessageBox.Show(this, TextResource.GetText("MSG_FAILED"));
                Close();
            }));
        }

        public void DownloadFinished(object? sender, EventArgs e)
        {
            active = false;
            Dispatcher.Invoke(new Action(() =>
            {
                MessageBox.Show(this, TextResource.GetText("MSG_UPDATED"));
                Close();
            }));
            this.Finished?.Invoke(sender, e);
        }

        public void DownloadProgressChanged(object? sender, ProgressResultEventArgs e)
        {
            Dispatcher.Invoke(new Action(() => Prg.Value = e.Progress));
        }

        public void DownloadStarted(object? sender, EventArgs e)
        {
            active = true;
        }

        public string Label
        {
            get => TxtHeading.Text;
            set => Dispatcher.Invoke(new Action(() => TxtHeading.Text = value));
        }

        public bool Inderminate
        {
            get => Prg.IsIndeterminate;
            set
            {
                Dispatcher.Invoke(new Action(() => Prg.IsIndeterminate = value));
            }
        }

        private void button1_Click(object sender, EventArgs e)
        {
            Cancelled?.Invoke(sender, e);
        }

        public void ShowNoUpdateMessage()
        {
            active = false;
            Dispatcher.Invoke(new Action(() =>
            {
                MessageBox.Show(this, TextResource.GetText("MSG_NO_UPDATE"));
                Close();
            }));
            this.Finished?.Invoke(this, EventArgs.Empty);
        }

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            NativeMethods.DisableMinMaxButton(this);
#if NET45_OR_GREATER
            if (XDM.Wpf.UI.App.Skin == Skin.Dark)
            {
                var helper = new WindowInteropHelper(this);
                helper.EnsureHandle();
                DarkModeHelper.UseImmersiveDarkMode(helper.Handle, true);
            }
#endif
        }
    }
}
