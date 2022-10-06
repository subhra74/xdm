using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Gtk;
using Application = Gtk.Application;
using IoPath = System.IO.Path;
using XDM.Core;
using Translations;
using UI = Gtk.Builder.ObjectAttribute;
using XDM.GtkUI.Utils;
using XDM.Core;
using XDM.Core.Util;
using TraceLog;
using XDM.Core.BrowserMonitoring;
using NativeMessaging;
using XDM.GtkUI.Dialogs.ChromeIntegrator;

namespace XDM.GtkUI.Dialogs.Settings
{
    internal class SettingsDialog : Dialog
    {
        //private int[] minVidSize = new int[] { 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768 };
        private WindowGroup group;
        [UI]
        private Label TabHeader1, TabHeader2, TabHeader3, TabHeader4, TabHeader5;
        [UI]
        private Label PageHeader1, PageHeader2, PageHeader3, PageHeader4, PageHeader5;
        [UI]
        private Label Label1, Label2, Label3, Label4, Label5, Label6, Label7,
            Label8, Label9, Label10, Label11, Label12, Label13, Label14, Label15,
            Label16, Label17, Label18, Label19, Label20, Label21,
            Label22, Label23, Label24, Label25, Label26, Label27,
            Label28, Label29, Label30;
        [UI]
        private LinkButton VideoWikiLink;
        [UI]
        Button BtnChrome, BtnFirefox, BtnEdge, BtnOpera, BtnBrave, BtnVivaldi, BtnDefault1, BtnDefault2,
            BtnDefault3, CatAdd, CatEdit, CatDel, CatDef, AddPass, EditPass, DelPass, BtnUserAgentReset,
            BtnCopy1, BtnCopy2, BtnCancel, BtnOK, BtnDownloadFolderBrowse, BtnTempFolderBrowse, BtnBrowse;
        [UI]
        private CheckButton ChkMonitorClipboard, ChkTimestamp, ChkDarkTheme, ChkAutoCat, ChkShowPrg,
            ChkShowComplete, ChkStartAuto, ChkOverwrite, ChkEnableSpeedLimit, ChkHalt, ChkKeepAwake,
            ChkRunCmd, ChkRunAntivirus, ChkAutoRun;
        [UI]
        private ComboBox CmbMinVidSize, CmbDblClickAction, CmbMaxParallalDownloads,
            CmbTimeOut, CmbMaxSegments, CmbMaxRetry, CmbProxyType;
        [UI]
        private Entry TxtChromeWebStoreUrl, TxtFirefoxAMOUrl, TxtTempFolder, TxtDownloadFolder,
            TxtMaxSpeedLimit, TxtProxyHost, TxtProxyPort, TxtProxyUser, TxtProxyPassword,
            TxtCustomCmd, TxtAntiVirusCmd, TxtAntiVirusArgs, TxtDefaultUserAgent,
            TxtExceptions, TxtDefaultVideoFormats, TxtDefaultFileTypes;
        [UI]
        private TreeView LvCategories, LvPasswords;

        [UI]
        private Notebook Tabs;

        [UI]
        private ListBox SideList;

        private ListStore categoryStore, passwordStore;

        private SettingsDialog(Builder builder,
            Window parent,
            WindowGroup group) : base(builder.GetRawOwnedObject("dialog"))
        {
            builder.Autoconnect(this);

            Modal = true;
            SetDefaultSize(640, 480);
            SetPosition(WindowPosition.CenterAlways);
            TransientFor = parent;
            this.group = group;
            this.group.AddWindow(this);
            GtkHelper.AttachSafeDispose(this);

            LoadTexts();

            SideList.RowSelected += SideList_RowSelected;

            Tabs.ShowTabs = false;

            Title = TextResource.GetText("TITLE_SETTINGS");
            GtkHelper.PopulateComboBoxGeneric<int>(CmbMinVidSize, new int[] { 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768 });
            GtkHelper.PopulateComboBoxGeneric<int>(CmbMaxParallalDownloads, Enumerable.Range(1, 50).ToArray());
            GtkHelper.PopulateComboBox(CmbDblClickAction, TextResource.GetText("CTX_OPEN_FOLDER"), TextResource.GetText("MSG_OPEN_FILE"));

            CreateCategoryListView();

            GtkHelper.PopulateComboBoxGeneric<int>(CmbTimeOut, Enumerable.Range(1, 300).ToArray());
            GtkHelper.PopulateComboBoxGeneric<int>(CmbMaxSegments, Enumerable.Range(1, 64).ToArray());
            GtkHelper.PopulateComboBoxGeneric<int>(CmbMaxRetry, Enumerable.Range(1, 100).ToArray());
            GtkHelper.PopulateComboBox(CmbProxyType, TextResource.GetText("NET_SYSTEM_PROXY"),
                TextResource.GetText("ND_NO_PROXY"), TextResource.GetText("ND_MANUAL_PROXY"));

            CreatePasswordManagerListView();

            VideoWikiLink.Clicked += VideoWikiLink_Clicked;
            BtnChrome.Clicked += BtnChrome_Clicked;
            BtnFirefox.Clicked += BtnFirefox_Clicked;
            BtnEdge.Clicked += BtnEdge_Clicked;
            BtnOpera.Clicked += BtnOpera_Clicked;
            BtnBrave.Clicked += BtnBrave_Clicked;
            BtnVivaldi.Clicked += BtnVivaldi_Clicked;
            BtnCopy1.Clicked += BtnCopy1_Clicked;
            BtnCopy2.Clicked += BtnCopy2_Clicked;
            BtnDefault1.Clicked += BtnDefault1_Clicked;
            BtnDefault2.Clicked += BtnDefault2_Clicked;
            BtnDefault3.Clicked += BtnDefault3_Clicked;

            BtnCopy1.Image = new Image(GtkHelper.LoadSvg("file-copy-line"));
            BtnCopy2.Image = new Image(GtkHelper.LoadSvg("file-copy-line"));

            BtnOK.Clicked += BtnOK_Clicked;
            BtnCancel.Clicked += BtnCancel_Clicked;

            BtnTempFolderBrowse.Clicked += BtnTempFolderBrowse_Clicked;
            BtnDownloadFolderBrowse.Clicked += BtnDownloadFolderBrowse_Clicked;

            CatAdd.Clicked += CatAdd_Clicked;
            CatEdit.Clicked += CatEdit_Clicked;
            CatDel.Clicked += CatDel_Clicked;
            CatDef.Clicked += CatDef_Clicked;

            CmbProxyType.Changed += CmbProxyType_Changed;

            AddPass.Clicked += AddPass_Clicked;
            DelPass.Clicked += DelPass_Clicked;
            EditPass.Clicked += EditPass_Clicked;

            BtnBrowse.Clicked += BtnBrowse_Clicked;
            BtnUserAgentReset.Clicked += BtnUserAgentReset_Clicked;
        }

        private void SideList_RowSelected(object o, RowSelectedArgs args)
        {
            var index = args.Row.Index;
            Tabs.Page = index;
        }

        private void BtnUserAgentReset_Clicked(object? sender, EventArgs e)
        {
            TxtDefaultUserAgent.Text = Config.DefaultFallbackUserAgent;
        }

        private void BtnBrowse_Clicked(object? sender, EventArgs e)
        {
            var file = GtkHelper.SelectFile(this);
            if (!string.IsNullOrEmpty(file))
            {
                TxtAntiVirusCmd.Text = file;
            }
        }

        private void EditPass_Clicked(object? sender, EventArgs e)
        {
            var passwd = GtkHelper.GetSelectedValue<PasswordEntry?>(this.LvPasswords, 2);
            if (passwd.HasValue)
            {
                using var dlg = PasswordDialog.CreateFromGladeFile(this, this.group);
                dlg.SetPassword(passwd.Value);
                dlg.Run();
                dlg.Destroy();
                if (dlg.Result)
                {
                    var password = new PasswordEntry
                    {
                        Host = dlg.Host,
                        User = dlg.UserName,
                        Password = dlg.Password
                    };
                    if (LvPasswords.Selection.GetSelected(out var iter))
                    {
                        passwordStore.SetValues(iter, password.Host, password.User, password);
                    }
                }
            }
        }

        private void DelPass_Clicked(object? sender, EventArgs e)
        {
            if (LvPasswords.Selection.GetSelected(out var iter))
            {
                passwordStore.Remove(ref iter);
            }
        }

        private void AddPass_Clicked(object? sender, EventArgs e)
        {
            using var dlg = PasswordDialog.CreateFromGladeFile(this, this.group);
            dlg.Run();
            dlg.Destroy();
            if (dlg.Result)
            {
                var password = new PasswordEntry
                {
                    Host = dlg.Host,
                    User = dlg.UserName,
                    Password = dlg.Password
                };
                passwordStore.AppendValues(password.Host, password.User, password);
            }
        }

        private void CmbProxyType_Changed(object? sender, EventArgs e)
        {
            TxtProxyUser.Sensitive = TxtProxyPassword.Sensitive = TxtProxyHost.Sensitive =
                TxtProxyPort.Sensitive = CmbProxyType.Active == 2;
        }

        private void CatDef_Clicked(object? sender, EventArgs e)
        {
            categoryStore.Clear();
            foreach (var cat in Config.DefaultCategories)
            {
                categoryStore.AppendValues(cat.DisplayName, string.Join(",", cat.FileExtensions), cat.DefaultFolder, cat);
            }
        }

        private void CatDel_Clicked(object? sender, EventArgs e)
        {
            if (LvCategories.Selection.GetSelected(out var iter))
            {
                categoryStore.Remove(ref iter);
            }
        }

        private void CatEdit_Clicked(object? sender, EventArgs e)
        {
            var cat = GtkHelper.GetSelectedValue<Category?>(this.LvCategories, 3);
            if (cat.HasValue)
            {
                using var dlg = CategoryEditDialog.CreateFromGladeFile(this, this.group);
                dlg.SetCategory(cat.Value);
                dlg.Run();
                dlg.Destroy();
                if (dlg.Result)
                {
                    var cat1 = new Category
                    {
                        Name = cat.Value.Name,
                        DisplayName = dlg.DisplayName!,
                        DefaultFolder = dlg.Folder!,
                        FileExtensions = new HashSet<string>(dlg.FileTypes!.Replace("\r\n", "")
                        .Split(',').Select(x => x.Trim()).Where(x => x.Length > 0))
                    };
                    if (LvCategories.Selection.GetSelected(out var iter))
                    {
                        categoryStore.SetValues(iter, cat1.DisplayName, string.Join(",", cat1.FileExtensions), cat1.DefaultFolder, cat1);
                    }
                }
            }
        }

        private void CatAdd_Clicked(object? sender, EventArgs e)
        {
            using var dlg = CategoryEditDialog.CreateFromGladeFile(this, this.group);
            dlg.Run();
            dlg.Destroy();
            if (dlg.Result)
            {
                var cat = new Category
                {
                    Name = Guid.NewGuid().ToString(),
                    DisplayName = dlg.DisplayName!,
                    DefaultFolder = dlg.Folder!,
                    FileExtensions = new HashSet<string>(dlg.FileTypes!.Replace("\r\n", "")
                    .Split(',').Select(x => x.Trim()).Where(x => x.Length > 0))
                };
                categoryStore.AppendValues(cat.DisplayName, string.Join(",", cat.FileExtensions), cat.DefaultFolder, cat);
            }
        }

        private void BtnDownloadFolderBrowse_Clicked(object? sender, EventArgs e)
        {
            var folder = GtkHelper.SelectFolder(this);
            if (!string.IsNullOrEmpty(folder))
            {
                TxtDownloadFolder.Text = folder;
            }
        }

        private void BtnTempFolderBrowse_Clicked(object? sender, EventArgs e)
        {
            var folder = GtkHelper.SelectFolder(this);
            if (!string.IsNullOrEmpty(folder))
            {
                TxtTempFolder.Text = folder;
            }
        }

        private void BtnCancel_Clicked(object? sender, EventArgs e)
        {
            Dispose();
        }

        private void BtnOK_Clicked(object? sender, EventArgs e)
        {
            UpdateBrowserMonitoringConfig();
            UpdateGeneralSettingsConfig();
            UpdateNetworkSettingsConfig();
            UpdatePasswordManagerConfig();
            UpdateAdvancedSettingsConfig();
            Config.SaveConfig();
            ApplicationContext.BroadcastConfigChange();
            Dispose();
            Helpers.RunGC();
        }

        private void BtnDefault3_Clicked(object? sender, EventArgs e)
        {
            TxtExceptions.Text = string.Join(",", Config.DefaultBlockedHosts);
        }

        private void BtnDefault2_Clicked(object? sender, EventArgs e)
        {
            TxtDefaultVideoFormats.Text = string.Join(",", Config.DefaultVideoExtensions);
        }

        private void BtnDefault1_Clicked(object? sender, EventArgs e)
        {
            TxtDefaultFileTypes.Text = string.Join(",", Config.DefaultFileExtensions);
        }

        private void BtnCopy2_Clicked(object? sender, EventArgs e)
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            if (cb != null)
            {
                cb.Text = TxtFirefoxAMOUrl.Text;
            }
        }

        private void BtnCopy1_Clicked(object? sender, EventArgs e)
        {
            var cb = Clipboard.Get(Gdk.Selection.Clipboard);
            if (cb != null)
            {
                cb.Text = TxtChromeWebStoreUrl.Text;
            }
        }

        private void BtnOpera_Clicked(object? sender, EventArgs e)
        {
            try
            {
                NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchOperaBrowser(Links.ManualExtensionInstallGuideUrl);
                ShowIntegrationWindow();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Opera");
                GtkHelper.ShowMessageBox(this, $"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Opera");
            }
        }

        private void BtnBrave_Clicked(object? sender, EventArgs e)
        {
            try
            {
                NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchBraveBrowser(Links.ManualExtensionInstallGuideUrl);
                ShowIntegrationWindow();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Brave");
                GtkHelper.ShowMessageBox(this, $"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Brave Browser");
            }
        }

        private void BtnVivaldi_Clicked(object? sender, EventArgs e)
        {
            try
            {
                NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchVivaldi(Links.ManualExtensionInstallGuideUrl);
                ShowIntegrationWindow();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Vivaldi");
                GtkHelper.ShowMessageBox(this, $"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Vivaldi");
            }
        }

        private void BtnEdge_Clicked(object? sender, EventArgs e)
        {
            try
            {
                NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchMicrosoftEdge(Links.ChromeExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error Microsoft Edge");
                GtkHelper.ShowMessageBox(this, $"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Microsoft Edge");
            }
        }

        private void BtnFirefox_Clicked(object? sender, EventArgs e)
        {
            try
            {
                NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Firefox);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchFirefox(Links.FirefoxExtensionUrl);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Firefox");
                GtkHelper.ShowMessageBox(this, $"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Firefox");
            }
        }

        private void ShowIntegrationWindow()
        {
            var wnd = ChromeIntegratorWindow.CreateFromGladeFile();
            wnd.Show();
        }

        private void BtnChrome_Clicked(object? sender, EventArgs e)
        {
            try
            {
                NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error installing native host");
                GtkHelper.ShowMessageBox(this, TextResource.GetText("MSG_NATIVE_HOST_FAILED"));
                return;
            }

            try
            {
                BrowserLauncher.LaunchGoogleChrome(Links.ManualExtensionInstallGuideUrl);
                ShowIntegrationWindow();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error launching Google Chrome");
                GtkHelper.ShowMessageBox(this, $"{TextResource.GetText("MSG_BROWSER_LAUNCH_FAILED")} Google Chrome");
            }
        }

        private void VideoWikiLink_Clicked(object? sender, EventArgs e)
        {
            PlatformHelper.OpenBrowser(Links.VideoDownloadTutorialUrl);
        }

        private void LoadTexts()
        {
            PageHeader1.Text = TextResource.GetText("SETTINGS_MONITORING");
            PageHeader2.Text = TextResource.GetText("SETTINGS_GENERAL");
            PageHeader3.Text = TextResource.GetText("SETTINGS_NETWORK");
            PageHeader4.Text = TextResource.GetText("SETTINGS_CRED");
            PageHeader5.Text = TextResource.GetText("SETTINGS_ADV");

            Label1.StyleContext.AddClass("medium-font");

            Label1.Text = TextResource.GetText("SETTINGS_MONITORING");
            Label2.Text = TextResource.GetText("DESC_MONITORING_1");
            Label3.Text = TextResource.GetText("MSG_VID_WIKI_TEXT");
            VideoWikiLink.Label = TextResource.GetText("MSG_VID_WIKI_LINK");

            Label4.Text = TextResource.GetText("DESC_OTHER_BROWSERS");
            Label5.Text = TextResource.GetText("DESC_CHROME");
            Label6.Text = TextResource.GetText("DESC_MOZ");

            //BtnChrome.Label = TextResource.GetText("MSG_VID_WIKI_LINK");
            //BtnFirefox.Label = TextResource.GetText("MSG_VID_WIKI_LINK");
            //BtnEdge.Label = TextResource.GetText("MSG_VID_WIKI_LINK");
            //BtnOpera.Label = TextResource.GetText("MSG_VID_WIKI_LINK");

            Label7.Text = TextResource.GetText("DESC_FILETYPES");
            Label8.Text = TextResource.GetText("DESC_VIDEOTYPES");
            Label9.Text = TextResource.GetText("DESC_SITEEXCEPTIONS");
            Label10.Text = TextResource.GetText("LBL_MIN_VIDEO_SIZE");

            BtnDefault1.Label = BtnDefault2.Label = BtnDefault3.Label = TextResource.GetText("DESC_DEF");

            ChkMonitorClipboard.Label = TextResource.GetText("MENU_CLIP_ADD");
            ChkTimestamp.Label = TextResource.GetText("LBL_GET_TIMESTAMP");

            Label11.StyleContext.AddClass("medium-font");

            Label11.Text = TextResource.GetText("SETTINGS_GENERAL");
            Label12.Text = TextResource.GetText("MSG_DOUBLE_CLICK_ACTION");
            Label13.Text = TextResource.GetText("LBL_TEMP_FOLDER");
            Label14.Text = TextResource.GetText("SETTINGS_FOLDER");
            Label15.Text = TextResource.GetText("MSG_MAX_DOWNLOAD");
            Label16.Text = TextResource.GetText("SETTINGS_CAT");

            ChkDarkTheme.Label = TextResource.GetText("SETTINGS_DARK_THEME");
            ChkAutoCat.Label = TextResource.GetText("SETTINGS_ATUO_CAT");
            ChkShowPrg.Label = TextResource.GetText("SHOW_DWN_PRG");
            ChkShowComplete.Label = TextResource.GetText("SHOW_DWN_COMPLETE");
            ChkStartAuto.Label = TextResource.GetText("LBL_START_AUTO");
            ChkOverwrite.Label = TextResource.GetText("LBL_OVERWRITE_EXISTING");
            CatAdd.Label = TextResource.GetText("SETTINGS_CAT_ADD");
            CatEdit.Label = TextResource.GetText("SETTINGS_CAT_EDIT");
            CatDel.Label = TextResource.GetText("DESC_DEL");
            CatDef.Label = TextResource.GetText("DESC_DEF");

            Label17.StyleContext.AddClass("medium-font");

            Label17.Text = TextResource.GetText("SETTINGS_NETWORK");
            Label18.Text = TextResource.GetText("DESC_NET1");
            Label19.Text = TextResource.GetText("DESC_NET2");
            Label20.Text = TextResource.GetText("NET_MAX_RETRY");
            Label21.Text = TextResource.GetText("DESC_NET4");
            Label22.Text = TextResource.GetText("PROXY_HOST");
            Label23.Text = TextResource.GetText("PROXY_PORT");
            Label24.Text = TextResource.GetText("DESC_NET7");
            Label25.Text = TextResource.GetText("DESC_NET8");

            ChkEnableSpeedLimit.Label = TextResource.GetText("MSG_SPEED_LIMIT");

            Label26.Text = TextResource.GetText("SETTINGS_CRED");
            Label26.StyleContext.AddClass("medium-font");

            AddPass.Label = TextResource.GetText("SETTINGS_CAT_ADD");
            EditPass.Label = TextResource.GetText("SETTINGS_CAT_EDIT");
            DelPass.Label = TextResource.GetText("DESC_DEL");

            Label27.Text = TextResource.GetText("SETTINGS_ADV");
            Label27.StyleContext.AddClass("medium-font");

            ChkHalt.Label = TextResource.GetText("MSG_HALT");
            ChkKeepAwake.Label = TextResource.GetText("MSG_AWAKE");
            ChkRunCmd.Label = TextResource.GetText("EXEC_CMD");
            ChkRunAntivirus.Label = TextResource.GetText("EXE_ANTI_VIR");
            ChkAutoRun.Label = TextResource.GetText("AUTO_START");
            BtnUserAgentReset.Label = TextResource.GetText("DESC_DEF");

            Label28.Text = TextResource.GetText("ANTIVIR_CMD");
            Label29.Text = TextResource.GetText("ANTIVIR_ARGS");
            Label30.Text = TextResource.GetText("MSG_FALLBACK_UA");

            BtnOK.Label = TextResource.GetText("DESC_SAVE_Q");
            BtnCancel.Label = TextResource.GetText("ND_CANCEL");
        }

        public void LoadConfig()
        {
            //Browser monitoring
            TxtChromeWebStoreUrl.Text = Links.ChromeExtensionUrl;
            TxtFirefoxAMOUrl.Text = Links.FirefoxExtensionUrl;
            TxtDefaultFileTypes.Text = string.Join(",", Config.Instance.FileExtensions);
            TxtDefaultVideoFormats.Text = string.Join(",", Config.Instance.VideoExtensions);
            TxtExceptions.Text = string.Join(",", Config.Instance.BlockedHosts);
            GtkHelper.SetSelectedComboBoxValue<int>(CmbMinVidSize, Config.Instance.MinVideoSize);
            ChkMonitorClipboard.Active = Config.Instance.MonitorClipboard;
            ChkTimestamp.Active = Config.Instance.FetchServerTimeStamp;

            //General settings
            ChkShowPrg.Active = Config.Instance.ShowProgressWindow;
            ChkShowComplete.Active = Config.Instance.ShowDownloadCompleteWindow;
            ChkStartAuto.Active = Config.Instance.StartDownloadAutomatically;
            ChkOverwrite.Active = Config.Instance.FileConflictResolution == FileConflictResolution.Overwrite;
            ChkDarkTheme.Active = Config.Instance.AllowSystemDarkTheme;
            TxtTempFolder.Text = Config.Instance.TempDir;
            GtkHelper.SetSelectedComboBoxValue<int>(CmbMaxParallalDownloads, Config.Instance.MaxParallelDownloads);
            ChkAutoCat.Active = Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto;
            TxtDownloadFolder.Text = Config.Instance.DefaultDownloadFolder;
            CmbDblClickAction.Active = Config.Instance.DoubleClickOpenFile ? 1 : 0;

            foreach (var cat in Config.Instance.Categories)
            {
                categoryStore.AppendValues(cat.DisplayName, string.Join(",", cat.FileExtensions), cat.DefaultFolder, cat);
            }

            //Network settings
            GtkHelper.SetSelectedComboBoxValue<int>(CmbTimeOut, Config.Instance.NetworkTimeout);
            GtkHelper.SetSelectedComboBoxValue<int>(CmbMaxSegments, Config.Instance.MaxSegments);
            GtkHelper.SetSelectedComboBoxValue<int>(CmbMaxRetry, Config.Instance.MaxRetry);
            TxtMaxSpeedLimit.Text = Config.Instance.DefaltDownloadSpeed.ToString();
            ChkEnableSpeedLimit.Active = Config.Instance.EnableSpeedLimit;
            CmbProxyType.Active = (int)(Config.Instance.Proxy?.ProxyType ?? ProxyType.System);
            TxtProxyHost.Text = Config.Instance.Proxy?.Host;
            TxtProxyPort.Text = (Config.Instance.Proxy?.Port ?? 0).ToString();
            TxtProxyUser.Text = Config.Instance.Proxy?.UserName;
            TxtProxyPassword.Text = Config.Instance.Proxy?.Password;

            //Password manager
            foreach (var password in Config.Instance.UserCredentials)
            {
                passwordStore.AppendValues(password.Host, password.User, password);
            }

            //Advanced settings
            ChkHalt.Active = Config.Instance.ShutdownAfterAllFinished;
            ChkKeepAwake.Active = Config.Instance.KeepPCAwake;
            ChkRunCmd.Active = Config.Instance.RunCommandAfterCompletion;
            ChkRunAntivirus.Active = Config.Instance.ScanWithAntiVirus;
            ChkAutoRun.Active = PlatformHelper.IsAutoStartEnabled();

            TxtCustomCmd.Text = Config.Instance.AfterCompletionCommand;
            TxtAntiVirusCmd.Text = Config.Instance.AntiVirusExecutable;
            TxtAntiVirusArgs.Text = Config.Instance.AntiVirusArgs;
            TxtDefaultUserAgent.Text = Config.Instance.FallbackUserAgent;
        }

        private void CreateCategoryListView()
        {
            categoryStore = new ListStore(typeof(string), typeof(string), typeof(string), typeof(Category));
            LvCategories.Model = categoryStore;

            var k = 0;
            foreach (var key in new string[] { "SETTINGS_CAT_NAME", "SETTINGS_CAT_TYPES", "SETTINGS_CAT_FOLDER" })
            {
                var cellRendererText = new CellRendererText();
                var treeViewColumn = new TreeViewColumn(TextResource.GetText(key), cellRendererText, "text", k++)
                {
                    Resizable = true,
                    Reorderable = false,
                    Sizing = TreeViewColumnSizing.Fixed,
                    FixedWidth = 150
                };
                LvCategories.AppendColumn(treeViewColumn);
            }

            LvCategories.Selection.Changed += CategorySelection_Changed;
        }

        private void CategorySelection_Changed(object? sender, EventArgs e)
        {
            var count = LvCategories.Selection.CountSelectedRows();
            CatEdit.Sensitive = CatDel.Sensitive = count > 0;
        }

        private void CreatePasswordManagerListView()
        {
            passwordStore = new ListStore(typeof(string), typeof(string), typeof(PasswordEntry));
            LvPasswords.Model = passwordStore;

            var k = 0;
            foreach (var key in new string[] { "DESC_HOST", "DESC_USER" })
            {
                var cellRendererText = new CellRendererText();
                var treeViewColumn = new TreeViewColumn(TextResource.GetText(key), cellRendererText, "text", k++)
                {
                    Resizable = true,
                    Reorderable = false,
                    Sizing = TreeViewColumnSizing.Fixed,
                    FixedWidth = 150
                };
                LvPasswords.AppendColumn(treeViewColumn);
            }
            LvPasswords.Selection.Changed += Selection_Changed; ;
        }

        private void Selection_Changed(object? sender, EventArgs e)
        {
            var count = LvPasswords.Selection.CountSelectedRows();
            EditPass.Sensitive = DelPass.Sensitive = count > 0;
        }

        private void UpdateBrowserMonitoringConfig()
        {
            Config.Instance.FileExtensions = TxtDefaultFileTypes.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.VideoExtensions = TxtDefaultVideoFormats.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.BlockedHosts = TxtExceptions.Text.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0).ToArray();
            Config.Instance.FetchServerTimeStamp = ChkTimestamp.Active;
            Config.Instance.MonitorClipboard = ChkMonitorClipboard.Active;
            Config.Instance.MinVideoSize = GtkHelper.GetSelectedComboBoxValue<int>(CmbMinVidSize);
        }

        private void UpdateGeneralSettingsConfig()
        {
            Config.Instance.ShowProgressWindow = ChkShowPrg.Active;
            Config.Instance.ShowDownloadCompleteWindow = ChkShowComplete.Active;
            Config.Instance.StartDownloadAutomatically = ChkStartAuto.Active;
            Config.Instance.FileConflictResolution =
                ChkOverwrite.Active ? FileConflictResolution.Overwrite : FileConflictResolution.AutoRename;
            Config.Instance.TempDir = TxtTempFolder.Text;
            Config.Instance.MaxParallelDownloads = GtkHelper.GetSelectedComboBoxValue<int>(CmbMaxParallalDownloads);
            Config.Instance.Categories = GtkHelper.GetListStoreValues<Category>(categoryStore, 3);
            Config.Instance.FolderSelectionMode = ChkAutoCat.Active ? FolderSelectionMode.Auto : FolderSelectionMode.Manual;
            Config.Instance.DefaultDownloadFolder = TxtDownloadFolder.Text;
            Config.Instance.AllowSystemDarkTheme = ChkDarkTheme.Active;
            Config.Instance.DoubleClickOpenFile = CmbDblClickAction.Active == 1;
        }

        private void UpdateNetworkSettingsConfig()
        {
            Config.Instance.NetworkTimeout = GtkHelper.GetSelectedComboBoxValue<int>(CmbTimeOut);
            Config.Instance.MaxSegments = GtkHelper.GetSelectedComboBoxValue<int>(CmbMaxSegments);
            Config.Instance.MaxRetry = GtkHelper.GetSelectedComboBoxValue<int>(CmbMaxRetry);
            if (Int32.TryParse(TxtMaxSpeedLimit.Text, out int speed))
            {
                Config.Instance.DefaltDownloadSpeed = speed;
            }
            Config.Instance.EnableSpeedLimit = ChkEnableSpeedLimit.Active;
            Int32.TryParse(TxtProxyPort.Text, out int port);
            Config.Instance.Proxy = new ProxyInfo
            {
                ProxyType = (ProxyType)CmbProxyType.Active,
                Host = TxtProxyHost.Text,
                UserName = TxtProxyUser.Text,
                Password = TxtProxyPassword.Text,
                Port = port
            };
        }

        private void UpdatePasswordManagerConfig()
        {
            Config.Instance.UserCredentials = GtkHelper.GetListStoreValues<PasswordEntry>(passwordStore, 2);
        }

        private void UpdateAdvancedSettingsConfig()
        {
            Config.Instance.ShutdownAfterAllFinished = ChkHalt.Active;
            Config.Instance.KeepPCAwake = ChkKeepAwake.Active;
            Config.Instance.RunCommandAfterCompletion = ChkRunCmd.Active;
            Config.Instance.ScanWithAntiVirus = ChkRunAntivirus.Active;
            PlatformHelper.EnableAutoStart(ChkAutoRun.Active);

            Config.Instance.AfterCompletionCommand = TxtCustomCmd.Text;
            Config.Instance.AntiVirusExecutable = TxtAntiVirusCmd.Text;
            Config.Instance.AntiVirusArgs = TxtAntiVirusArgs.Text;
            Config.Instance.FallbackUserAgent = TxtDefaultUserAgent.Text;
        }

        public void SetActivePage(int page)
        {
            Tabs.Page = page;
            var row = SideList.GetRowAtIndex(page);
            SideList.SelectRow(row);
        }

        public static SettingsDialog CreateFromGladeFile(Window parent, WindowGroup group)
        {
            var builder = new Builder();
            builder.AddFromFile(IoPath.Combine(AppDomain.CurrentDomain.BaseDirectory, "glade", "settings-dialog.glade"));
            return new SettingsDialog(builder, parent, group);
        }
    }
}
