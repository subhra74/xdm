using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Drawing.Text;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using System.Drawing.Drawing2D;
using XDM.Common.UI;
using TraceLog;
using System.Collections.Specialized;
using XDMApp;
using XDM.Core.Lib.UI;
using Translations;
using XDM.WinForm.UI.FormHelper;

#if !(NET472_OR_GREATER || NET5_0_OR_GREATER)
using static XDM.WinForm.UI.WinFormsPolyfill;
#endif

namespace XDM.WinForm.UI
{
    public partial class AppWinPeer : Form, IAppWinPeer, IClipboardMonitor
    {
        public static Color ProgressBackColor = Color.FromArgb(230, 230, 230);
        public static Color ProgressForeColor = Color.DodgerBlue;
        public static bool AppsUseLightTheme = true;
        public static Bitmap? MenuMargin;

        private Font remixIconFont;
        private DownloadDB downloadsDB;
        private Category? categoryFilter;
        private const int WM_NCHITTEST = 0x84;
        private const int HTCLIENT = 0x1;
        private const int HTCAPTION = 0x2;
        private const int HT_CLIENT = 0x1;
        private const int HT_CAPTION = 0x2;
        public static int WM_NCLBUTTONDOWN = 0xA1;
        private bool drag = false; // determine if we should be moving the form
        private Point startPoint = new Point(0, 0); // also for the moving
        private NotifyIcon trayIcon;
        private Font buttonFont;
        //private PrivateFontCollection fcIconMoon, fcFontAwesome, fcRemixIcon;
        private bool darkMode;
        private string scheduledIcon, waitingIcon;
        private Font ri16Font, ri14Font, ri12Font, fa16Font, fa10Font;
        private string searchText;
        public event EventHandler ClipboardChanged;
        private IButton newButton, deleteButton, pauseButton, resumeButton, openFileButton, openFolderButton;
        private IMenuItem[] menuItems;
        private Timer runAfterShown;

        private IFormColors FormColors;

        public event EventHandler<CategoryChangedEventArgs>? CategoryChanged;
        public event EventHandler? InProgressContextMenuOpening;
        public event EventHandler? FinishedContextMenuOpening;
        public event EventHandler? SelectionChanged;
        public event EventHandler? NewDownloadClicked;
        public event EventHandler? YoutubeDLDownloadClicked;
        public event EventHandler? BatchDownloadClicked;
        public event EventHandler? SettingsClicked;
        public event EventHandler? ClearAllFinishedClicked;
        public event EventHandler? ExportClicked;
        public event EventHandler? ImportClicked;
        public event EventHandler? BrowserMonitoringButtonClicked;
        public event EventHandler? BrowserMonitoringSettingsClicked;
        public event EventHandler? UpdateClicked;
        public event EventHandler? HelpClicked;
        public event EventHandler? SupportPageClicked;
        public event EventHandler? BugReportClicked;
        public event EventHandler? CheckForUpdateClicked;
        public event EventHandler? SchedulerClicked;
        public event EventHandler? MoveToQueueClicked;

        public IMenuItem[] MenuItems { get => menuItems; }
        public Dictionary<string, IMenuItem> MenuItemMap { get; private set; }

        public AppWinPeer()
        {
            remixIconFont = new Font(GlobalFontCollection.RiFontInstance.Families[0], 24);
            ri16Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 16); //new Font(fcFontAwesome.Families[0], 16);
            ri14Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 14); //new Font(fcFontAwesome.Families[0], 16);
            ri12Font = new Font(GlobalFontCollection.RiFontInstance.Families[0], 12); //new Font(fcFontAwesome.Families[0], 16);
            fa16Font = new Font(GlobalFontCollection.FaFontInstance.Families[0], 14);
            fa10Font = new Font(GlobalFontCollection.FaFontInstance.Families[0], 10);
            scheduledIcon = RemixIcon.GetFontIcon(RemixIcon.ScheduledFileIcon);
            waitingIcon = RemixIcon.GetFontIcon(RemixIcon.ScheduledFileIcon);

            this.InitializeComponent();

            AppsUseLightTheme = !Config.Instance.AllowSystemDarkTheme || !ImmersiveThemeHelper.IsDarkThemeActive();
            MenuMargin = new Bitmap(LogicalToDeviceUnits(16), LogicalToDeviceUnits(16));

            FormColors = AppsUseLightTheme ? new FormColorsLight() : new FormColorsDark();

            ProgressBackColor = FormColors.ProgressBarBackColor;
            ProgressForeColor = FormColors.ProgressBarForeColor;

            downloadsDB = new(dgActiveList, dgCompletedList);

            this.DoubleBuffered = true;

            var fontAwesomeFont2 = new Font(GlobalFontCollection.ImFontInstance.Families[0], 12);
            buttonFont = fontAwesomeFont2;

            panel3.BackColor = panel6.BackColor = FormColors.BorderColor;

            var paddingVar = Math.Max((int)Math.Floor(this.Font.Height * 0.2), 2);

            Shown += (_, _) =>
            {
                runAfterShown = new Timer { Interval = 1000 };
                runAfterShown.Tick += (x, y) =>
                {
                    Helpers.RunGC();
                    runAfterShown.Stop();
                    runAfterShown.Dispose();
                    runAfterShown = null;
                };
                runAfterShown.Start();
            };

            FormClosed += (_, _) => Helpers.RunGC();
            Resize += (_, _) =>
            {
                if (WindowState == FormWindowState.Minimized) Helpers.RunGC();
            };

            CreateToolbar();

            CreateTrayIcon();

            CreateDataGridView();

            SetupContextMenu();

            //SetupMainMenu();

            UpdateParallalismLabel();

            CreateMenuItems();

            LayoutMenuItems();

            if (!IsHandleCreated)
            {
                this.CreateHandle();
            }

            CreateFooter();

            label3.Margin = new Padding(LogicalToDeviceUnits(5), 0, LogicalToDeviceUnits(5), 0);
            panel6.Padding = new Padding(0, LogicalToDeviceUnits(1), 0, 0);
            panel5.Padding = new Padding(0, LogicalToDeviceUnits(1), 0, 0);
            panel3.Padding = new Padding(0, LogicalToDeviceUnits(1), 0, 0);
            panel4.Padding = new Padding(
                LogicalToDeviceUnits(0),
                LogicalToDeviceUnits(5),
                LogicalToDeviceUnits(2),
                LogicalToDeviceUnits(5));

            if (!AppsUseLightTheme)
            {
                EnableDarkMode();
            }

            MenuHelper.CustomizeMenuAppearance(ctxMainMenu);
            MenuHelper.CustomizeMenuAppearance(ctxDownloadMenu);
            MenuHelper.CustomizeMenuAppearance(ctxMenuActiveList);
            MenuHelper.CustomizeMenuAppearance(ctxMenuCompletedList);
            MenuHelper.CustomizeMenuAppearance(ctxMenuNotifyIcon);
        }

        private void CreateFooter()
        {
            btnMonitoring.Font = this.fa16Font;
            btnMonitoring.ForeColor = FormColors.IconColor;
            btnMonitoring.Text = RemixIcon.GetFontIcon("f205");

            btnHelp.Image = CreateToolbarIcon(this.ri12Font,
                RemixIcon.GetFontIcon(RemixIcon.HelpIcon), FormColors.IconColor, LogicalToDeviceUnits(10));

            btnParallel.Image = CreateToolbarIcon(this.ri12Font,
                RemixIcon.GetFontIcon(RemixIcon.SettingsIcon), FormColors.IconColor, LogicalToDeviceUnits(10));

            label3.ForeColor = btnParallel.ForeColor = btnHelp.ForeColor = FormColors.FooterForeColor;
            panel5.BackColor = FormColors.BorderColor;
            tableLayoutPanel2.BackColor = FormColors.FooterBackColor;

            ButtonHelper.SetFlatStyle(this.btnParallel, FormColors);
            ButtonHelper.SetFlatStyle(this.btnHelp, FormColors);
            ButtonHelper.SetFlatStyle(this.btnMonitoring, FormColors);
        }

        private void CreateToolbar()
        {
            tableLayoutPanel1.BackColor = FormColors.ToolbarBackColor;
            ButtonHelper.ParentBackColor = tableLayoutPanel1.BackColor;
            var toolbarImageFont = this.ri12Font;
            var fg1 = FormColors.ToolbarButtonForeColor;
            var fg2 = FormColors.ToolbarButtonDisabledForeColor;

            this.btnNew.ForeColor = fg1;
            ButtonHelper.SetFlatStyle(this.btnNew, FormColors);
            this.btnNew.Image = CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.LinkIcon),
                                        fg1, 2);
            //Log.Debug("Height1: " + LogicalToDeviceUnits(3) + " height2: " + ((Math.Ceiling(btnNew.Image.Height * 0.5))));
            //var buttonPadding = new Padding(LogicalToDeviceUnits(3));
            var height = Math.Max((int)(Math.Ceiling(btnNew.Image.Height * 0.5)), LogicalToDeviceUnits(3));
            var buttonPadding = new Padding(LogicalToDeviceUnits(3), height, LogicalToDeviceUnits(3), height);

            ButtonHelper.ButtonStateIcons = new()
            {
                [this.btnDelete] = (ImgEnabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.RemoveIcon),
                                        fg1, 2),
                                    ImgDisabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.RemoveIcon),
                                        fg2, 2)
                                    ),
                [this.btnOpenFolder] = (ImgEnabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.FolderOpenIcon),
                                        fg1, 2),
                                    ImgDisabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.FolderOpenIcon),
                                        fg2, 2)
                                    ),
                [this.btnResume] = (ImgEnabled:
                                       CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.ResumeIcon),
                                        fg1, 2),
                                    ImgDisabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.ResumeIcon),
                                        fg2, 2)
                                    ),
                [this.btnOpenFile] = (ImgEnabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.FileOpenIcon),
                                        fg1, 2),
                                    ImgDisabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.FileOpenIcon),
                                        fg2, 2)
                                    ),
                [this.btnPause] = (ImgEnabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.PauseIcon),
                                        fg1, 2),
                                    ImgDisabled:
                                        CreateToolbarIcon(toolbarImageFont,
                                        RemixIcon.GetFontIcon(RemixIcon.PauseIcon),
                                        fg2, 2)
                                    )
            };

            //this.btnDelete.Font = new Font(fontCollection5.Families[0], 12); //
            this.btnDelete.Image = ButtonHelper.ButtonStateIcons[this.btnDelete].ImgEnabled;
            this.btnDelete.Padding = buttonPadding;
            this.btnDelete.ForeColor = fg1;//Color.FromArgb(190, 190, 190); //
            this.btnDelete.Text = "Delete";//((char)Int32.Parse("f1f8"/*RemixIcon.RemoveIcon*//*"ec28"*//*"eb99"*/, System.Globalization.NumberStyles.HexNumber)).ToString();//((char)Int32.Parse("f1f8", System.Globalization.NumberStyles.HexNumber)).ToString();

            //this.btnOpenFolder.Font = new Font(fontCollection5.Families[0], 12); //
            this.btnOpenFolder.ForeColor = fg1;//Color.FromArgb(190, 190, 190); //
            this.btnOpenFolder.Image = ButtonHelper.ButtonStateIcons[this.btnOpenFolder].ImgEnabled;
            this.btnOpenFolder.Padding = buttonPadding;
            this.btnOpenFolder.Text = "Open folder";

            //this.btnOpenFile.Font = new Font(fontCollection5.Families[0], 12); //
            this.btnOpenFile.ForeColor = fg1;// Color.FromArgb(190, 190, 190); //
            this.btnOpenFile.Image = ButtonHelper.ButtonStateIcons[this.btnOpenFile].ImgEnabled;
            this.btnOpenFile.Padding = buttonPadding;
            this.btnOpenFile.Text = "Open file";

            //this.btnResume.Font = new Font(fontCollection5.Families[0], 12); //
            this.btnResume.Image = ButtonHelper.ButtonStateIcons[this.btnResume].ImgEnabled;
            this.btnResume.Padding = buttonPadding;
            this.btnResume.ForeColor = fg1;// Color.FromArgb(190, 190, 190); //
            this.btnResume.Text = "Resume";

            //this.btnPause.Font = new Font(fontCollection5.Families[0], 12); //
            this.btnPause.ForeColor = fg1;//Color.FromArgb(190, 190, 190); //
            this.btnPause.Text = "Pause";
            this.btnPause.Image = ButtonHelper.ButtonStateIcons[this.btnPause].ImgEnabled;
            this.btnPause.Padding = buttonPadding;

            this.btnMenu.Font = toolbarImageFont; //
            this.btnMenu.ForeColor = fg1;//Color.FromArgb(190, 190, 190); //
            this.btnMenu.Text = RemixIcon.GetFontIcon(RemixIcon.MenuIcon);//((char)Int32.Parse("f0c9"/*RemixIcon.MenuIcon*/, System.Globalization.NumberStyles.HexNumber)).ToString();//((char)Int32.Parse("f04c", System.Globalization.NumberStyles.HexNumber)).ToString();

            ButtonHelper.SetFlatStyle(btnMenu, FormColors);

            this.btnSearch.Font = fa10Font; //
            this.btnSearch.Padding = new Padding(0);
            this.btnSearch.ForeColor = FormColors.SearchButtonColor;//Color.FromArgb(190, 190, 190); //
            this.btnSearch.Text = ((char)Int32.Parse("f002"/*RemixIcon.SearchIcon*/, System.Globalization.NumberStyles.HexNumber)).ToString();//((char)Int32.Parse("f04c", System.Globalization.NumberStyles.HexNumber)).ToString();

            btnNew.Tag = "enabled";

            newButton = new ButtonWrapper(this.btnNew, FormColors);
            deleteButton = new ButtonWrapper(this.btnDelete, FormColors);
            pauseButton = new ButtonWrapper(this.btnPause, FormColors);
            resumeButton = new ButtonWrapper(this.btnResume, FormColors);
            openFileButton = new ButtonWrapper(this.btnOpenFile, FormColors);
            openFolderButton = new ButtonWrapper(this.btnOpenFolder, FormColors);

            this.btnSearch.Click += (_, _) =>
            {
                Search();
            };

            ButtonHelper.SetFlatStyle(btnSearch, FormColors);

            this.btnMenu.Click += (a, b) =>
            {
                ctxMainMenu.Show(btnMenu, new Point(btnMenu.Width - ctxMainMenu.Width, btnMenu.Height));
            };

#if !NET5_0_OR_GREATER
            textBox1.HandleCreated += (s, e) =>
            {
                SendMessage(textBox1.Handle, EM_SETCUEBANNER, 0, TextResource.GetText("LBL_SEARCH") ?? "Search");
            };
#endif

            textBox1.BackColor = FormColors.TextBackColor;
            textBox1.ForeColor = FormColors.TextForeColor;

            panel4.BackColor = FormColors.ToolbarBackColor;
            panel9.BackColor = FormColors.BorderColor;
            tableLayoutPanel3.BackColor = FormColors.TextBackColor;
            btnSearch.BackColor = FormColors.TextBackColor;
            btnSearch.ForeColor = FormColors.SearchButtonColor;

            textBox1.Margin = new Padding(LogicalToDeviceUnits(3), 0, 0, 0);
            tableLayoutPanel3.Padding = new Padding(LogicalToDeviceUnits(3), 0, 0, 0);
            panel9.Padding = new Padding(LogicalToDeviceUnits(1));

            btnNew.Padding = new Padding(LogicalToDeviceUnits(6));
            textBox1.Margin = new Padding(LogicalToDeviceUnits(3));
            btnSearch.Margin = new Padding(LogicalToDeviceUnits(3));

            btnMenu.Padding = new Padding(
                LogicalToDeviceUnits(5),
                LogicalToDeviceUnits(8),
                LogicalToDeviceUnits(5),
                LogicalToDeviceUnits(8));
            tableLayoutPanel2.Padding = new Padding(LogicalToDeviceUnits(5));
        }

        private void StyleDataGridView(DataGridView dataGridView, bool inProgress)
        {
            //var dgvType = dataGridView.GetType();
            //var pi = dgvType.GetProperty("DoubleBuffered",
            //      BindingFlags.Instance | BindingFlags.NonPublic);
            //pi?.SetValue(dataGridView, true, null);

            var padding = new Padding(LogicalToDeviceUnits(5));
            var headerPadding = new Padding(LogicalToDeviceUnits(5));//, LogicalToDeviceUnits(5), 0, LogicalToDeviceUnits(5));

            dataGridView.ColumnHeadersDefaultCellStyle.Padding = headerPadding;// new Padding(0, LogicalToDeviceUnits(10), 0, LogicalToDeviceUnits(10));
            //dataGridView.DefaultCellStyle.Padding = padding;
            dataGridView.Columns[0].DefaultCellStyle.Padding = new Padding(LogicalToDeviceUnits(1));
            dataGridView.Columns[0].DefaultCellStyle.Alignment = DataGridViewContentAlignment.MiddleRight;
            // dataGridView.Columns[0].DefaultCellStyle.BackColor = Color.Red;
            dataGridView.Columns[0].DefaultCellStyle.Font = ri16Font;

            dataGridView.Columns[1].DefaultCellStyle.Padding = new Padding(LogicalToDeviceUnits(1));

            dataGridView.CellBorderStyle = DataGridViewCellBorderStyle.None;

            //var col = dataGridView.Columns[0];
            //col.DefaultCellStyle.Font = /*inProgress ? fa16Font :*/ ri16Font;
            ////dataGridView.Columns[1].DefaultCellStyle.Padding = new Padding(LogicalToDeviceUnits(0), LogicalToDeviceUnits(4),
            ////                LogicalToDeviceUnits(2), LogicalToDeviceUnits(4));
            var sizeCol = dataGridView.Columns[inProgress ? "SizeCol" : "CompletedSizeCol"];
            sizeCol.DefaultCellStyle.Alignment = DataGridViewContentAlignment.MiddleLeft;
            sizeCol.HeaderCell.Style.Alignment = DataGridViewContentAlignment.MiddleLeft;
            //if (!inProgress)
            //{
            //    sizeCol.DefaultCellStyle.Padding = new Padding(LogicalToDeviceUnits(4), LogicalToDeviceUnits(4),
            //        LogicalToDeviceUnits(6), LogicalToDeviceUnits(4));
            //    sizeCol.HeaderCell.Style.Padding = new Padding(LogicalToDeviceUnits(4), LogicalToDeviceUnits(4),
            //    LogicalToDeviceUnits(6), LogicalToDeviceUnits(4));
            //}
            if (inProgress)
            {
                sizeCol.SortMode = DataGridViewColumnSortMode.NotSortable;
            }
            //if (inProgress)
            //{
            //    //dataGridView.CellFormatting += (a, b) =>
            //    //{
            //    //    if (b.ColumnIndex == 3)
            //    //    {
            //    //        long sz = (long)b.Value;
            //    //        b.Value = sz > 0 ? Helpers.FormatSize(sz) : string.Empty;
            //    //    }
            //    //    if (b.ColumnIndex == 2)
            //    //    {
            //    //        var dt = (DateTime)b.Value;
            //    //        b.Value = dt.ToShortDateString();
            //    //    }
            //    //    if (b.ColumnIndex == 6)
            //    //    {
            //    //        var rowIndex = b.RowIndex;
            //    //        try
            //    //        {
            //    //            var ent = this.downloadsDB.InProgressItems3[rowIndex];
            //    //            var text = Helpers.GenerateStatusText(ent.DownloadEntry);
            //    //            b.Value = text;
            //    //        }
            //    //        catch { }
            //    //    }
            //    //};
            //}

            if (inProgress)
            {
                dataGridView.CellMouseEnter += (a, b) =>
                {
                    if (b.ColumnIndex == 0)
                    {
                        var rowIndex = b.RowIndex;
                        try
                        {
                            var ent = this.downloadsDB.InProgressItems[rowIndex];
                            dataGridView.Rows[rowIndex].Cells[0].ToolTipText = ent.Status.ToString();
                        }
                        catch { }
                    }

                };
            }

            dataGridView.MouseUp += (o, e) =>
            {
                if (dataGridView.HitTest(e.X, e.Y) == DataGridView.HitTestInfo.Nowhere)
                {
                    dataGridView.ClearSelection();
                }
            };

            dataGridView.DefaultCellStyle.BackColor = FormColors.DataGridViewBackColor;
            dataGridView.DefaultCellStyle.ForeColor = FormColors.DataGridViewForeColor;
            dataGridView.DefaultCellStyle.SelectionForeColor = FormColors.DataGridViewSelectionForeColor;
            dataGridView.DefaultCellStyle.SelectionBackColor = FormColors.DataGridViewSelectionBackColor;
            dataGridView.DefaultCellStyle.ForeColor = FormColors.DataGridViewForeColor;
            dataGridView.ColumnHeadersDefaultCellStyle.BackColor = FormColors.DataGridViewBackColor;
            dataGridView.ColumnHeadersDefaultCellStyle.ForeColor = FormColors.DataGridViewHeaderForeColor;

            dataGridView.Columns[0].DefaultCellStyle.ForeColor = FormColors.IconColor;
            dataGridView.Columns[0].DefaultCellStyle.SelectionForeColor = FormColors.IconColor;

            dataGridView.BackgroundColor = FormColors.DataGridViewBackColor;
        }

        private void Search()
        {
            searchText = textBox1.Text;
            var category = IsInProgressViewSelected ? null : (Category?)dgCategories.SelectedRows[0].Cells[1].Value;
            if (IsInProgressViewSelected)
            {
                this.downloadsDB.InProgressItems.UpdateView(searchText);
                this.dgActiveList.RowCount = 0;
                this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
                this.dgActiveList.Refresh();
                this.dgActiveList.ClearSelection();
            }
            else
            {
                this.downloadsDB.FinishedItems.UpdateView(searchText, category);
                this.dgCompletedList.RowCount = 0;
                this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
                this.dgCompletedList.Refresh();
                this.dgCompletedList.ClearSelection();
            }
        }

        private void CreateDataGridView()
        {
            var h12 = Math.Max(ri16Font.Height, dgCompletedList.DefaultCellStyle.Font.Height) + LogicalToDeviceUnits(12);
            var h22 = Math.Max(ri16Font.Height, dgActiveList.DefaultCellStyle.Font.Height) + LogicalToDeviceUnits(12);
            var h = Math.Max(h12, h22);
            dgCompletedList.RowTemplate.Height = h;// Math.Max(ri16Font.Height, dgCompletedList.DefaultCellStyle.Font.Height) + LogicalToDeviceUnits(12);
            dgActiveList.RowTemplate.Height = h;// Math.Max(ri16Font.Height, dgActiveList.DefaultCellStyle.Font.Height) + LogicalToDeviceUnits(12);

            dgActiveList.Columns.Insert(4, new DataGridViewProgressColumn(LogicalToDeviceUnits(1))
            {
                Width = LogicalToDeviceUnits(50),
                Name = "PrgCol",
                HeaderText = "%",
                DataPropertyName = "Progress",
                Resizable = DataGridViewTriState.True,
                ReadOnly = true,
                AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill,
                ScaleFactor = LogicalToDeviceUnits(1),
                FillWeight = 2,
                MinimumWidth = LogicalToDeviceUnits(50)
            });

            StyleDataGridView(dgActiveList, true);
            StyleDataGridView(dgCompletedList, false);

            Log.Debug("Columns creating");

            this.dgCompletedList.Columns["CompletedImgCol"].Width = LogicalToDeviceUnits(40);
            this.dgCompletedList.Columns["CompletedNameCol"].MinimumWidth = LogicalToDeviceUnits(300);
            this.dgCompletedList.Columns["CompletedNameCol"].FillWeight = 96;
            this.dgCompletedList.Columns["CompletedDateCol"].MinimumWidth = LogicalToDeviceUnits(100);
            this.dgCompletedList.Columns["CompletedDateCol"].FillWeight = 2;
            this.dgCompletedList.Columns["CompletedSizeCol"].MinimumWidth = LogicalToDeviceUnits(100);
            this.dgCompletedList.Columns["CompletedSizeCol"].FillWeight = 2;
            //this.dgCompletedList.Columns["CompletedImgCol"].DefaultCellStyle.Alignment =
            //    DataGridViewContentAlignment.MiddleCenter;

            this.dgCompletedList.VirtualMode = true;
            this.dgCompletedList.CellValueNeeded += DgCompletedList_CellValueNeeded;
            this.dgCompletedList.ColumnHeaderMouseClick += DgCompletedList_ColumnHeaderMouseClick;

            this.dgActiveList.Columns["ImgCol"].Width = LogicalToDeviceUnits(40);
            this.dgActiveList.Columns["NameCol"].MinimumWidth = LogicalToDeviceUnits(100);
            this.dgActiveList.Columns["NameCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill;
            this.dgActiveList.Columns["NameCol"].FillWeight = 90;
            this.dgActiveList.Columns["ActiveDateCol"].MinimumWidth = LogicalToDeviceUnits(80);
            this.dgActiveList.Columns["ActiveDateCol"].FillWeight = 2;
            this.dgActiveList.Columns["SizeCol"].MinimumWidth = LogicalToDeviceUnits(70);
            this.dgActiveList.Columns["SizeCol"].FillWeight = 2;
            this.dgActiveList.Columns["StatusCol"].MinimumWidth = LogicalToDeviceUnits(60);
            this.dgActiveList.Columns["StatusCol"].FillWeight = 2;
            this.dgActiveList.Columns["StatusCol"].Visible = false;
            this.dgActiveList.Columns["SpeedCol"].MinimumWidth = LogicalToDeviceUnits(140);
            this.dgActiveList.Columns["SpeedCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill;
            this.dgActiveList.Columns["SpeedCol"].FillWeight = 2;
            this.dgActiveList.Columns["EtaCol"].MinimumWidth = LogicalToDeviceUnits(60);
            this.dgActiveList.Columns["EtaCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            this.dgActiveList.Columns["EtaCol"].Visible = false;
            //this.dgActiveList.Columns["ImgCol"].DefaultCellStyle.Alignment = DataGridViewContentAlignment.MiddleCenter;

            this.dgActiveList.VirtualMode = true;
            this.dgActiveList.CellValueNeeded += DgActiveList_CellValueNeeded;
            this.dgActiveList.ColumnHeaderMouseClick += DgActiveList_ColumnHeaderMouseClick;


            //this.dgActiveList.Columns["ImgCol"].Width = LogicalToDeviceUnits(40);
            //this.dgActiveList.Columns["NameCol"].Width = LogicalToDeviceUnits(200);
            //this.dgActiveList.Columns["NameCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            //this.dgActiveList.Columns["ActiveDateCol"].Width = LogicalToDeviceUnits(80);
            //this.dgActiveList.Columns["ActiveDateCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            //this.dgActiveList.Columns["SizeCol"].Width = LogicalToDeviceUnits(70);
            //this.dgActiveList.Columns["SizeCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            //this.dgActiveList.Columns["StatusCol"].Width = LogicalToDeviceUnits(60);
            //this.dgActiveList.Columns["StatusCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            //this.dgActiveList.Columns["StatusCol"].Visible = false;
            //this.dgActiveList.Columns["SpeedCol"].Width = LogicalToDeviceUnits(120);
            //this.dgActiveList.Columns["SpeedCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            //this.dgActiveList.Columns["EtaCol"].Width = LogicalToDeviceUnits(60);
            //this.dgActiveList.Columns["EtaCol"].AutoSizeMode = DataGridViewAutoSizeColumnMode.None;
            //this.dgActiveList.Columns["EtaCol"].Visible = false;
            //this.dgActiveList.Columns["ImgCol"].DefaultCellStyle.Alignment = DataGridViewContentAlignment.MiddleCenter;

            dgState.Columns[0].DefaultCellStyle.Font = this.ri14Font; //
            dgState.Rows.Add(((char)Int32.Parse("ea4c", System.Globalization.NumberStyles.HexNumber)).ToString(), "incomplete");
            dgState.Rows.Add(((char)Int32.Parse("eb7b", System.Globalization.NumberStyles.HexNumber)).ToString(), "complete");

            var h1 = (int)Math.Ceiling(this.Font.Height * 0.4);
            var w1 = (int)Math.Ceiling(this.Font.Height * 0.2);

            dgCategories.Columns[0].DefaultCellStyle.Font = this.ri14Font;
            string GetFontIcon(string name)
            {
                switch (name)
                {
                    case "CAT_DOCUMENTS":
                        return RemixIcon.GetFontIcon(RemixIcon.DocumentIconLine);
                    case "CAT_MUSIC":
                        return RemixIcon.GetFontIcon(RemixIcon.MusicIconLine);
                    case "CAT_VIDEOS":
                        return RemixIcon.GetFontIcon(RemixIcon.VideoIconLine);
                    case "CAT_COMPRESSED":
                        return RemixIcon.GetFontIcon(RemixIcon.ArchiveIconLine);
                    case "CAT_PROGRAMS":
                        return RemixIcon.GetFontIcon(RemixIcon.AppIconLine);
                    default:
                        return RemixIcon.GetFontIcon(RemixIcon.OtherFileIconLine);
                }
            }

            dgCategories.CellFormatting += (a, b) =>
            {
                if (b.ColumnIndex == 1)
                {
                    var category = (Category)b.Value;
                    b.Value = category.IsPredefined ?
                    TextResource.GetText(category.Name) : category.DisplayName;
                }
            };

            foreach (var category in Config.Instance.Categories)
            {
                dgCategories.Rows.Add(GetFontIcon(category.Name), category);
            }

            dgCategories.ClearSelection();

            dgState.SelectionChanged += (a, b) =>
            {
                if (dgState.SelectedRows.Count < 1) return;

                var index = dgState.SelectedRows[0].Index;

                if (dgCategories.SelectedRows.Count > 0)
                {
                    dgCategories.ClearSelection();
                }

                if (index == 0)
                {
                    ClearCategoryFilter();
                    dgActiveList.BringToFront();
                    //panel4.Visible = false;
                    CategoryChanged?.Invoke(this, new CategoryChangedEventArgs { Level = 0, Index = 0 });
                }
                else
                {
                    ClearCategoryFilter();
                    dgCompletedList.BringToFront();
                    //panel4.Visible = true;
                    CategoryChanged?.Invoke(this, new CategoryChangedEventArgs { Level = 1, Index = 1 });
                }

                SelectionChanged?.Invoke(this, EventArgs.Empty);
            };

            this.dgState.Columns[0].DefaultCellStyle.Padding = new System.Windows.Forms.Padding(2 * h1, h1, 0, h1);
            this.dgState.Columns[1].DefaultCellStyle.Padding = new System.Windows.Forms.Padding(w1, h1, 0, h1);

            this.dgCategories.Columns[0].DefaultCellStyle.Padding = new System.Windows.Forms.Padding(4 * h1, h1, 0, h1);
            this.dgCategories.Columns[1].DefaultCellStyle.Padding = new System.Windows.Forms.Padding(w1, h1, 0, h1);
        }

        private void DgActiveList_CellValueNeeded(object sender, DataGridViewCellValueEventArgs e)
        {
            var row = e.RowIndex;
            if (row >= downloadsDB.InProgressItems.RowCount || row < 0)
            {
                e.Value = string.Empty;
                return;
            }

            try
            {
                var download = downloadsDB.InProgressItems[row];
                switch (e.ColumnIndex)
                {
                    case 0:
                        e.Value = IconResource.GetFontIconForFileType(download.Name ?? string.Empty);
                        break;
                    case 1:
                        e.Value = download.Name;
                        break;
                    case 2:
                        e.Value = download.DateAdded.ToShortDateString();
                        break;
                    case 3:
                        e.Value = Helpers.FormatSize(download.Size);
                        break;
                    case 4:
                        e.Value = download.Progress;
                        break;
                    case 6:
                        try
                        {
                            e.Value = Helpers.GenerateStatusText(download.DownloadEntry);
                        }
                        catch { }
                        break;
                    default:
                        e.Value = string.Empty;
                        break;
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error with index: " + row + " View row count: " + downloadsDB.InProgressItems.RowCount);
                e.Value = string.Empty;
                return;
            }
        }

        private void DgCompletedList_CellValueNeeded(object sender, DataGridViewCellValueEventArgs e)
        {
            // Log.Debug("Row: " + e.RowIndex);

            var row = e.RowIndex;
            if (row >= downloadsDB.FinishedItems.RowCount || row < 0)
            {
                e.Value = string.Empty;
                return;
            }
            try
            {
                var download = downloadsDB.FinishedItems[row];
                switch (e.ColumnIndex)
                {
                    case 0:
                        e.Value = IconResource.GetFontIconForFileType(download.Name ?? string.Empty);
                        break;
                    case 1:
                        e.Value = download.Name;
                        break;
                    case 2:
                        e.Value = download.DateAdded.ToShortDateString();
                        break;
                    case 3:
                        e.Value = Helpers.FormatSize(download.Size);
                        break;
                    default:
                        e.Value = string.Empty;
                        break;
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error with index: " + row + " View row count: " + downloadsDB.FinishedItems.RowCount);
                e.Value = string.Empty;
                return;
            }
        }

        private void DgActiveList_ColumnHeaderMouseClick(object sender, DataGridViewCellMouseEventArgs e)
        {
            var col = e.ColumnIndex;
            if (col < 1) return;
            var currentSort = dgActiveList.Columns[col].HeaderCell.SortGlyphDirection;
            if (currentSort == SortOrder.Ascending)
            {
                currentSort = SortOrder.Descending;
            }
            else if (currentSort == SortOrder.Descending)
            {
                currentSort = SortOrder.Ascending;
            }
            else
            {
                currentSort = SortOrder.Ascending;
            }
            this.downloadsDB.InProgressItems.UpdateView(searchText,
                new SortParam
                {
                    SortField = col switch
                    {
                        1 => SortField.Name,
                        2 => SortField.Modified,
                        3 => SortField.Size,
                        _ => SortField.Modified
                    },
                    IsAscending = currentSort == SortOrder.Ascending
                });
            foreach (DataGridViewColumn column in this.dgActiveList.Columns)
            {
                column.HeaderCell.SortGlyphDirection = SortOrder.None;
            }
            dgActiveList.Columns[col].HeaderCell.SortGlyphDirection = currentSort;
            dgActiveList.Refresh();
            this.dgActiveList.ClearSelection();
        }

        private void DgCompletedList_ColumnHeaderMouseClick(object sender, DataGridViewCellMouseEventArgs e)
        {
            var col = e.ColumnIndex;
            if (col < 1) return;
            var currentSort = dgCompletedList.Columns[col].HeaderCell.SortGlyphDirection;
            if (currentSort == SortOrder.Ascending)
            {
                currentSort = SortOrder.Descending;
            }
            else if (currentSort == SortOrder.Descending)
            {
                currentSort = SortOrder.Ascending;
            }
            else
            {
                currentSort = SortOrder.Ascending;
            }
            this.downloadsDB.FinishedItems.UpdateView(searchText, null,
                new SortParam
                {
                    SortField = col switch
                    {
                        1 => SortField.Name,
                        2 => SortField.Modified,
                        3 => SortField.Size,
                        _ => SortField.Modified
                    },
                    IsAscending = currentSort == SortOrder.Ascending
                });
            foreach (DataGridViewColumn column in this.dgCompletedList.Columns)
            {
                column.HeaderCell.SortGlyphDirection = SortOrder.None;
            }
            dgCompletedList.Columns[col].HeaderCell.SortGlyphDirection = currentSort;
            dgCompletedList.Refresh();
            this.dgCompletedList.ClearSelection();
        }

        //private void UpdateToolbarButtonState()
        //{
        //    DisableButton(btnOpenFile);
        //    DisableButton(btnOpenFolder);
        //    DisableButton(btnPause);
        //    DisableButton(btnResume);
        //    DisableButton(btnDelete);

        //    if (dgState.SelectedRows.Count == 1 && dgState.SelectedRows[0].Index == 0)
        //    {
        //        btnOpenFile.Visible = btnOpenFolder.Visible = false;
        //        btnResume.Visible = btnPause.Visible = true;
        //        var selectedRows = dgActiveList.SelectedRows;
        //        if (selectedRows.Count > 0)
        //        {
        //            EnableButton(btnDelete);
        //        }
        //        if (selectedRows.Count > 1)
        //        {
        //            EnableButton(btnResume);
        //            EnableButton(btnPause);
        //        }
        //        else if (selectedRows.Count == 1)
        //        {
        //            var ent = this.GetSelectedInProgressEntry();//selectedRows[0].Cells[1].Value as InProgressDownloadEntry;
        //            if (ent == null) return;
        //            var isActive = App.IsDownloadActive(ent.Id);
        //            Log.Information("Selected item active: " + isActive);
        //            if (isActive)
        //            {
        //                EnableButton(btnPause);
        //            }
        //            else
        //            {
        //                EnableButton(btnResume);
        //            }
        //        }
        //    }
        //    else
        //    {
        //        btnOpenFile.Visible = btnOpenFolder.Visible = true;
        //        btnPause.Visible = btnResume.Visible = false;
        //        if (dgCompletedList.SelectedRows.Count > 0)
        //        {
        //            EnableButton(btnDelete);
        //        }

        //        if (dgCompletedList.SelectedRows.Count == 1)
        //        {
        //            EnableButton(btnOpenFile);
        //            EnableButton(btnOpenFolder);
        //        }
        //    }
        //}

        //void SetFlatStyle(Button button)
        //{

        //    button.FlatAppearance.MouseOverBackColor = darkMode ? Color.FromArgb(40, 40, 40) :
        //        Color.FromArgb(230, 230, 230);

        //    button.FlatAppearance.MouseDownBackColor = darkMode ? Color.FromArgb(30, 30, 30) :
        //        Color.FromArgb(226, 226, 226);

        //}

        //private void DisableButton(Button button)
        //{
        //    button.ForeColor = darkMode ? Color.FromArgb(50, 50, 50) : Color.DarkGray;
        //    button.Image = buttonStateIcons[button].ImgDisabled;
        //    button.FlatAppearance.MouseOverBackColor = tableLayoutPanel1.BackColor;
        //    button.FlatAppearance.MouseDownBackColor = tableLayoutPanel1.BackColor;

        //    button.Tag = "disabled";
        //}

        //private void EnableButton(Button button)
        //{
        //    button.ForeColor = darkMode ? Color.Gray : Color.DimGray;/*Color.FromArgb(200, 200, 200);*/
        //    SetFlatStyle(button);
        //    button.Image = buttonStateIcons[button].ImgEnabled;
        //    button.Tag = "enabled";
        //}

        private void Form2_Load(object sender, EventArgs e)
        {
            Log.Debug("LogicalToDeviceUnits(5): " + LogicalToDeviceUnits(5));


            var height = 0;
            for (int i = 0; i < dgState.Rows.Count; i++)
            {
                height += dgState.Rows[i].Height;
            }
            dgState.Height = height;

            height = 0;

            dgCategories.Top = dgState.Height;

            for (int i = 0; i < dgCategories.Rows.Count; i++)
            {
                height += dgCategories.Rows[i].Height;
            }
            dgCategories.Height = height;
            dgState.Rows[1].Selected = true;

            dgActiveList.ClearSelection();
            dgCompletedList.ClearSelection();

            dgCompletedList.SelectionChanged += (a, b) =>
            {
                SelectionChanged?.Invoke(this, EventArgs.Empty);
            };

            dgActiveList.SelectionChanged += (a, b) =>
            {
                SelectionChanged?.Invoke(this, EventArgs.Empty);
            };

            if (dgCompletedList.Rows.Count > 0)
            {
                dgCompletedList.FirstDisplayedScrollingRowIndex = 0;
            }

            if (dgActiveList.Rows.Count > 0)
            {
                dgActiveList.FirstDisplayedScrollingRowIndex = 0;
            }

            dgCategories.SelectionChanged += (a, b) =>
            {
                if (dgCategories.SelectedRows.Count < 1) return;
                dgState.ClearSelection();
                dgCompletedList.BringToFront();
                ApplyCategoryFilter();
                CategoryChanged?.Invoke(this, new CategoryChangedEventArgs
                {
                    Level = 2,
                    Index = dgCategories.SelectedRows[0].Index,
                    Category = (Category)dgCategories.Rows[0].Cells[1].Value
                });
            };

            UpdateBrowserMonitorButton();

            LoadTexts();
        }

        //}

        ////private void SetToolstripIcon(ToolStripButton btn, string fontAwesomeCode)
        ////{
        ////    string text = ((char)Int32.Parse(fontAwesomeCode, System.Globalization.NumberStyles.HexNumber)).ToString();
        ////    btn.Font = this.fontAwesomeFont;
        ////    btn.Text = text;
        ////}

        ////private void ResumeDownloads()
        ////{
        ////    var idDict = new Dictionary<string, BaseDownloadEntry>();
        ////    var list = GetInProgressSelectedItems();
        ////    foreach (var item in list)
        ////    {
        ////        idDict[item.DownloadEntry.Id] = item.DownloadEntry;
        ////    }
        ////    //for (int i = 0; i < this.dgActiveList.SelectedRows.Count; i++)
        ////    //{
        ////    //    var row = this.dgActiveList.SelectedRows[i];
        ////    //    var entry = row.Cells[1].Value as BaseDownloadEntry;
        ////    //    idDict[entry.Id] = entry;
        ////    //}
        ////    App.ResumeDownload(idDict);
        ////}

        //public void ResumeDownload(string downloadId)
        //{
        //    var idDict = new Dictionary<string, BaseDownloadEntry>();
        //    var download = this.downloadsDB.InProgressItems.FindDownload(downloadId);
        //    if (download == null) return;
        //    idDict[download.DownloadEntry.Id] = download.DownloadEntry;
        //    //for (int i = 0; i < this.dgActiveList.Rows.Count; i++)
        //    //{
        //    //    var row = this.dgActiveList.Rows[i];
        //    //    var entry = row.Cells[1].Value as BaseDownloadEntry;
        //    //    if (entry.Id == downloadId)
        //    //    {
        //    //        idDict[entry.Id] = entry;
        //    //        break;
        //    //    }
        //    //}
        //    App.ResumeDownload(idDict);
        //}

        //private List<FinishedDownloadEntry> GetFinishedSelectedItems()
        //{
        //    var dgView = this.dgCompletedList;
        //    var arr = new List<FinishedDownloadEntry>(dgView.SelectedRows.Count);
        //    for (int i = 0; i < dgView.SelectedRows.Count; i++)
        //    {
        //        var row = dgView.SelectedRows[i];
        //        var entry = this.downloadsDB.FinishedItems[row.Index];// row.Cells[1].Value as BaseDownloadEntry;
        //        if (entry != null)
        //        {
        //            arr.Add(entry);
        //        }
        //    }
        //    return arr;
        //}

        //private List<InProgressDownloadEntryBinder> GetInProgressSelectedItems()
        //{
        //    var dgView = this.dgActiveList;
        //    var arr = new List<InProgressDownloadEntryBinder>(dgView.SelectedRows.Count);
        //    for (int i = 0; i < dgView.SelectedRows.Count; i++)
        //    {
        //        var row = dgView.SelectedRows[i];
        //        var entry = this.downloadsDB.InProgressItems[row.Index];// row.Cells[1].Value as BaseDownloadEntry;
        //        if (entry != null)
        //        {
        //            arr.Add(entry);
        //        }
        //    }
        //    return arr;
        //}

        //public void DeleteDownloads(bool inProgressOnly)
        //{
        //    if (inProgressOnly)
        //    {
        //        var selectedItems = GetInProgressSelectedItems();
        //        App.StopDownloads(selectedItems.Select(x => x.DownloadEntry.Id));
        //        if (MessageBox.Show(this,
        //            $"Delete {selectedItems.Count} item{(selectedItems.Count > 1 ? "s" : "")}?", "XDM", MessageBoxButtons.YesNo, MessageBoxIcon.Question)
        //            == DialogResult.Yes)
        //        {
        //            foreach (var item in selectedItems)
        //            {
        //                if (item != null)
        //                {
        //                    this.downloadsDB.InProgressItems.Delete(item);
        //                    App.RemoveDownload(item.DownloadEntry, false);
        //                }
        //            }
        //        }
        //    }
        //    else
        //    {
        //        var selectedRows = GetFinishedSelectedItems(); //new DataGridViewRow[dgCompletedList.SelectedRows.Count];
        //        //var indexes = new int[selectedRows.Length];
        //        if (MessageBox.Show(this,
        //            $"Delete {selectedRows.Count} item{(selectedRows.Count > 1 ? "s" : "")}?", "XDM", MessageBoxButtons.YesNo, MessageBoxIcon.Question)
        //            == DialogResult.Yes)
        //        {
        //            foreach (var selectedRow in selectedRows)
        //            {
        //                App.RemoveDownload(selectedRow, false);
        //            }
        //            //for (int i = 0; i < dgCompletedList.SelectedRows.Count; i++)
        //            //{
        //            //    var row = dgCompletedList.SelectedRows[i];
        //            //    selectedRows[i] = row;
        //            //    indexes[i] = row.Index;

        //            //    App.RemoveDownload(this.downloadsDB.FinishedItems[row.Index], false);

        //            //    //if (row.Cells[1].Value is FinishedDownloadEntry entry)
        //            //    //{
        //            //    //    App.RemoveDownload(entry, false);
        //            //    //}
        //            //}
        //            var firstVisibleRowIndex = this.dgCompletedList.FirstDisplayedScrollingRowIndex;
        //            this.downloadsDB.FinishedItems.Delete(selectedRows);
        //            this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
        //            //this.dgCompletedList.RowCount = 0;
        //            //this.dgCompletedList.Rows.Clear();
        //            //if (this.dgCompletedList.RowCount > 0)
        //            //{
        //            //    this.dgCompletedList.FirstDisplayedScrollingRowIndex =
        //            //        Math.Min(firstVisibleRowIndex, this.dgCompletedList.RowCount - 1);
        //            //}
        //            //this.dgCompletedList.FirstDisplayedScrollingRowIndex = firstVisibleRowIndex;
        //            //var totalVisible = this.dgCompletedList.DisplayedRowCount(true);
        //            //var index = this.dgCompletedList.FirstDisplayedScrollingRowIndex;
        //            //this.downloadsDB.FinishedItems.Delete(indexes);
        //            //this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
        //            //if (index < this.downloadsDB.FinishedItems.RowCount)
        //            //{
        //            //    this.dgCompletedList.FirstDisplayedScrollingRowIndex = index;
        //            //}

        //            this.dgCompletedList.Refresh();
        //        }
        //    }
        //    this.downloadsDB.InProgressItems.Save();
        //    //this.App.SaveFinishedList(this.tabComplete.AsEnumerable().Select(row => row["NameCol"] as FinishedDownloadEntry).ToList());
        //    //this.App.SaveInProgressList(this.tabIncomplete.AsEnumerable().Select(row => row["NameCol"] as InProgressDownloadEntry).ToList());
        //}

        //public void SetInProgressDownloadList(List<InProgressDownloadEntry> downloadEntries)
        //{
        //    //Log.Debug("Setting unfinished list");
        //    //this.dgActiveList.SuspendLayout();
        //    //foreach (var entry in downloadEntries)
        //    //{
        //    //    entry.Status = DownloadStatus.Stopped;
        //    //    this.InProgressDict[entry.Id] = this.tabIncomplete.Rows.Add(
        //    //        Path.GetExtension(entry.Name),
        //    //        entry, entry.DateAdded, entry.Size, entry.Progress, "Stopped");

        //    //}
        //    //this.dgActiveList.ResumeLayout();
        //    //this.dgActiveList.ClearSelection();
        //}

        //public void SetFinishedDownloadList(List<FinishedDownloadEntry> downloadEntries)
        //{
        //    //Log.Debug("Setting completed list");
        //    //this.dgCompletedList.SuspendLayout();
        //    //foreach (var entry in downloadEntries)
        //    //{
        //    //    if (categoryFilter == null)
        //    //    {
        //    //        this.tabComplete.Rows.Add(Path.GetExtension(entry.Name),
        //    //             entry, entry.DateAdded, entry.Size);
        //    //    }
        //    //    else
        //    //    {
        //    //        var ext = Path.GetExtension(entry.Name)?.ToUpperInvariant();
        //    //        if (categoryFilter.Value.FileExtensions.Contains(ext))
        //    //        {
        //    //            this.tabComplete.Rows.Add(Path.GetExtension(entry.Name),
        //    //             entry, entry.DateAdded, entry.Size);
        //    //        }
        //    //    }
        //    //}
        //    //this.dgCompletedList.ResumeLayout();
        //    //this.dgCompletedList.ClearSelection();
        //}

        //private void UpdateProgressOnUI(string id, int progress, double speed, long eta)
        //{
        //    var downloadEntry = this.downloadsDB.InProgressItems.FindDownload(id);// InProgressDict[id]["NameCol"] as InProgressDownloadEntry;
        //    if (downloadEntry != null)
        //    {
        //        downloadEntry.Progress = progress;
        //        downloadEntry.DownloadSpeed = Helpers.FormatSize(speed) + "/s";
        //        downloadEntry.ETA = Helpers.ToHMS(eta);
        //        this.downloadsDB.InProgressItems.Save();
        //    }
        //    //InProgressDict[id].SetField<int>("PrgCol", progress);
        //}

        //public void UpdateProgress(string id, int progress, double speed, long eta)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(updateProgressAction, id, progress, speed, eta);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            UpdateProgressOnUI(id, progress, speed, eta);
        //        }
        //    }
        //}

        //private void DownloadFinishedOnUI(string id, long finalFileSize, string filePath)
        //{
        //    Log.Debug("Final file name: " + filePath);
        //    var download = this.downloadsDB.InProgressItems.FindDownload(id);
        //    if (download == null) return;
        //    var downloadEntry = download?.DownloadEntry;
        //    if (downloadEntry == null) return;
        //    downloadEntry.Progress = 100;
        //    //InProgressDict[id].SetField<int>("PrgCol", 100);

        //    var finishedEntry = new FinishedDownloadEntry
        //    {
        //        Name = Path.GetFileName(filePath),// downloadEntry.Name,
        //        Id = downloadEntry.Id,
        //        Category = downloadEntry.Category,
        //        DateAdded = downloadEntry.DateAdded,
        //        Size = downloadEntry.Size > 0 ? downloadEntry.Size : finalFileSize,
        //        DownloadType = downloadEntry.DownloadType,
        //        TargetDir = Path.GetDirectoryName(filePath)!,
        //        PrimaryUrl = downloadEntry.PrimaryUrl,
        //        Authentication = downloadEntry.Authentication,
        //        Proxy = downloadEntry.Proxy
        //    };

        //    this.downloadsDB.FinishedItems.Add(finishedEntry);

        //    //this.dgCompletedList.SuspendLayout();
        //    //this.tabComplete.Rows.Add(
        //    //    Path.GetExtension(finishedEntry.Name),
        //    //    finishedEntry, downloadEntry.DateAdded, finishedEntry.Size);
        //    //this.dgCompletedList.ResumeLayout();

        //    //this.dgActiveList.SuspendLayout();

        //    this.downloadsDB.InProgressItems.Delete(download!);

        //    //this.dgActiveList.ResumeLayout();
        //    //this.InProgressDict.Remove(id);

        //    //this.App.SaveFinishedList(GetFinishedList());
        //    //this.App.SaveInProgressList(GetInprogressList());

        //    Log.Debug("dgState.SelectedRows[0].Selected " + dgState.SelectedRows[0].Selected + " this.App.ActiveDownloadCount: " + this.App.ActiveDownloadCount);

        //    if (this.App.ActiveDownloadCount == 0 &&
        //        dgState.SelectedRows.Count > 0 &&
        //        dgState.SelectedRows[0].Index == 0)
        //    {
        //        Log.Debug("switching to finished listview");
        //        //switch to finished view
        //        dgState.Rows[1].Selected = true;
        //    }
        //    else
        //    {
        //        Log.Debug("refreshing listview");
        //        //already on switched view, refresh
        //        //dgCompletedList.RowCount = 0;
        //        //this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;

        //        this.dgCompletedList.SuspendLayout();
        //        this.dgCompletedList.RowCount = 0;
        //        this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
        //        this.dgCompletedList.Refresh();
        //        this.dgCompletedList.ClearSelection();
        //        this.dgCompletedList.ResumeLayout();
        //    }

        //    //if (this.App.ActiveDownloadCount > 0)
        //    //{
        //    //    dgState.Rows[0].Selected = true;
        //    //}
        //    //else
        //    //{
        //    //    dgState.Rows[1].Selected = true;
        //    //}

        //    //dgCompletedList.ClearSelection();
        //    //dgCompletedList.Rows[0].Selected = true;
        //    //dgCompletedList.
        //}

        ////public IEnumerable<InProgressDownloadEntry> GetInprogressList()
        ////{
        ////    return this.downloadsDB.InProgressItems.
        ////    for (var i = 0; i < this.tabIncomplete.Rows.Count; i++)
        ////    {
        ////        yield return this.tabIncomplete.Rows[i]["NameCol"] as InProgressDownloadEntry;
        ////    }
        ////}

        ////public IEnumerable<FinishedDownloadEntry> GetFinishedList()
        ////{
        ////    //return downloadsDB.FinishedItems.
        ////    //for (var i = 0; i < this.tabComplete.Rows.Count; i++)
        ////    //{
        ////    //    yield return this.tabComplete.Rows[i]["NameCol"] as FinishedDownloadEntry;
        ////    //}
        ////}

        //public void DownloadFinished(string id, long finalFileSize, string filePath)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(this.downloadFinishedAction, id, finalFileSize, filePath);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            this.DownloadFinishedOnUI(id, finalFileSize, filePath);
        //        }
        //    }
        //}

        //private void DownloadFailedOnUI(string id)
        //{
        //    var download = this.downloadsDB.InProgressItems.FindDownload(id);// InProgressDict[id]["NameCol"] as InProgressDownloadEntry;
        //    if (download == null) return;
        //    download.Status = DownloadStatus.Stopped;
        //    this.downloadsDB.InProgressItems.Save();
        //    dgState.Rows[0].Selected = true;
        //    UpdateToolbarButtonState();
        //    //InProgressDict[id].SetField<string>("StatusCol", "Stopped");

        //    //this.App.SaveInProgressList(GetInprogressList());
        //}

        //public void DownloadFailed(string id)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(this.downloadStoppedAction, id);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            this.DownloadFailedOnUI(id);
        //        }
        //    }
        //}

        //public void DownloadCanelled(string id)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(this.downloadStoppedAction, id);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            this.DownloadFailedOnUI(id);
        //        }
        //    }
        //}

        //public void DownloadStarted(string id)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(this.downloadStartedAction, id);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            this.DownloadStartedOnUI(id);
        //        }
        //    }
        //}

        //private void DownloadStartedOnUI(string id)
        //{
        //    var download = this.downloadsDB.InProgressItems.FindDownload(id);// InProgressDict[id]["NameCol"] as InProgressDownloadEntry;
        //    if (download == null) return;
        //    download.Status = DownloadStatus.Downloading;
        //    this.downloadsDB.InProgressItems.Save();
        //    //InProgressDict[id].SetField("ImgCol",
        //    //    Win32FontIconCache.GetFontIconForFileType(downloadEntry.Name));
        //    //InProgressDict[id].SetField<string>("StatusCol", "Downloading");
        //    //this.App.SaveInProgressList(this.tabIncomplete.AsEnumerable().Select(
        //    //    row => row["NameCol"] as InProgressDownloadEntry).ToList());
        //    UpdateToolbarButtonState();
        //}

        //public void AddItemToTop(string id, string targetFileName, DateTime date,
        //    long fileSize, string type, FileNameFetchMode fileNameFetchMode,
        //    string primaryUrl, DownloadStartType startType,
        //    AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
        //{
        //    Log.Debug("Scheduled: " + startType + " schedule: " + schedule);
        //    var downloadEntry = new InProgressDownloadEntry
        //    {
        //        Name = targetFileName,
        //        Category = type,
        //        DateAdded = date,
        //        DownloadType = type,
        //        Id = id,
        //        Progress = 0,
        //        Size = fileSize,
        //        Status = DownloadStatus.Waiting,
        //        TargetDir = "",
        //        PrimaryUrl = primaryUrl,
        //        Authentication = authentication,
        //        Proxy = proxyInfo,
        //        MaxSpeedLimitInKiB = maxSpeedLimit,
        //        Schedule = schedule
        //    };
        //    this.downloadsDB.InProgressItems.Add(downloadEntry);

        //    //var label = startType == DownloadStartType.Waiting ? "Waiting" :
        //    //    (startType == DownloadStartType.Scheduled ? "Scheduling" :
        //    //    Path.GetExtension(targetFileName));
        //    //this.InProgressDict[id] = this.tabIncomplete.Rows.Add(label, new InProgressDownloadEntry
        //    //{
        //    //    Name = targetFileName,
        //    //    Category = type,
        //    //    DateAdded = date,
        //    //    DownloadType = type,
        //    //    Id = id,
        //    //    Progress = 0,
        //    //    Size = fileSize,
        //    //    Status = DownloadStatus.Stopped,
        //    //    TargetDir = "",
        //    //    PrimaryUrl = primaryUrl,
        //    //    Authentication = authentication,
        //    //    Proxy = proxyInfo,
        //    //    MaxSpeedLimitInKiB = maxSpeedLimit,
        //    //    Schedule = schedule
        //    //}, date, fileSize, 0, startType == DownloadStartType.Scheduled ? "Scheduled" : "Waiting");
        //    //this.App.SaveInProgressList(this.tabIncomplete.AsEnumerable().Select(row => row["NameCol"] as InProgressDownloadEntry).ToList());
        //    dgState.Rows[0].Selected = true;
        //    dgActiveList.ClearSelection();
        //    dgActiveList.Rows[0].Selected = true;
        //}

        //private void NewDownloadClicked()
        //{
        //    NewDownloadDialogHelper.CreateAndShowDialog(this.App, this, CreateNewDownloadDialog(true));
        //}

        //public void UpdateItem(string id, string targetFileName, long size)
        //{
        //    if (this.InvokeRequired)
        //    {
        //        this.BeginInvoke(this.updateItemCallBack, id, targetFileName, size);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            this.UpdateItemInvoke(id, targetFileName, size);
        //        }
        //    }
        //}

        //private void NewDownloadClicked(object sender, ToolStripItemClickedEventArgs e)
        //{

        //}

        //private void btnClose_Click(object sender, EventArgs e)
        //{
        //    this.Close();
        //}

        ////private void btnMaximize_Click(object sender, EventArgs e)
        ////{
        ////    if (this.WindowState == FormWindowState.Maximized)
        ////    {
        ////        this.WindowState = FormWindowState.Normal;
        ////        this.button18.Image = this.bmpMaximize;
        ////    }
        ////    else
        ////    {
        ////        this.WindowState = FormWindowState.Maximized;
        ////        this.button18.Image = this.bmpRestore;
        ////    }
        ////}

        //private void btnMinimize_Click(object sender, EventArgs e)
        //{
        //    this.WindowState = FormWindowState.Minimized;
        //}

        //private void UpdateItemInvoke(string id, string targetFileName, long size)
        //{
        //    var downloadEntry = this.downloadsDB.InProgressItems.FindDownload(id);//InProgressDict[id]["NameCol"] as InProgressDownloadEntry;
        //    if (downloadEntry == null) return;
        //    downloadEntry.Name = targetFileName;
        //    downloadEntry.Size = size;
        //    this.downloadsDB.InProgressItems.Save();
        //    //InProgressDict[id].SetField("ImgCol", Win32FontIconCache.GetFontIconForFileType(targetFileName));
        //    //InProgressDict[id].SetField("NameCol", downloadEntry);
        //    //InProgressDict[id].SetField("SizeCol", size);
        //    //this.App.SaveInProgressList(this.tabIncomplete.AsEnumerable().Select(row => row["NameCol"] as InProgressDownloadEntry).ToList());
        //}



        //Bitmap Create3(Font font, string text, int size)
        //{
        //    var bitmap = new Bitmap(font.Height, font.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);

        //    //ScaleBitmapLogicalToDevice(ref bitmap);
        //    Graphics g = Graphics.FromImage(bitmap);
        //    g.SmoothingMode = SmoothingMode.AntiAlias;
        //    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        //    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        //    g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;

        //    TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
        //        TextFormatFlags.VerticalCenter;
        //    TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), Color.Black, flags);
        //    return bitmap;
        //}

        //static Bitmap Create2(Font font, string text, int size)
        //{
        //    var bitmap = new Bitmap(font.Height, font.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        //    Graphics g = Graphics.FromImage(bitmap);
        //    //g.SmoothingMode = SmoothingMode.AntiAlias;
        //    //g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        //    //g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        //    g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        //    g.TextContrast = 10;

        //    TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
        //        TextFormatFlags.VerticalCenter;
        //    TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), Color.Gray, flags);
        //    return bitmap;
        //}

        //protected void Mouse_DownHandler(object sender, MouseEventArgs e)
        //{
        //    if (WindowState != FormWindowState.Maximized)
        //    {
        //        //ctrl-leftclick anywhere on the control to drag the form to a new location 
        //        if (e.Button == MouseButtons.Left)// && Control.ModifierKeys == Keys.Control)
        //        {
        //            ReleaseCapture();
        //            SendMessage(Handle, WM_NCLBUTTONDOWN, HT_CAPTION, 0);
        //        }
        //    }
        //}

        [DllImport("user32.dll")]
        public static extern int SendMessage(IntPtr hWnd, int Msg, int wParam, int lParam);

        //[DllImport("user32.dll")]
        //public static extern bool ReleaseCapture();

        //[DllImport("Gdi32.dll", EntryPoint = "CreateRoundRectRgn")]
        //private static extern IntPtr CreateRoundRectRgn
        //(
        //    int nLeftRect,     // x-coordinate of upper-left corner
        //    int nTopRect,      // y-coordinate of upper-left corner
        //    int nRightRect,    // x-coordinate of lower-right corner
        //    int nBottomRect,   // y-coordinate of lower-right corner
        //    int nWidthEllipse, // width of ellipse
        //    int nHeightEllipse // height of ellipse
        //);

        //protected override CreateParams CreateParams
        //{
        //    get
        //    {
        //        //const int CS_DROPSHADOW = 0x20000;
        //        CreateParams cp = base.CreateParams;
        //        //cp.ClassStyle |= CS_DROPSHADOW;
        //        //int WS_SYSMENU = 0x80000;
        //        int WS_MINIMIZEBOX = 0x20000;
        //        int WS_MAXIMIZEBOX = 0x10000;
        //        cp.Style |= (cp.Style |= (/*WS_SYSMENU |*/ WS_MINIMIZEBOX | WS_MAXIMIZEBOX));
        //        return cp;
        //    }
        //}


        //    private const int
        //HTLEFT = 10,
        //HTRIGHT = 11,
        //HTTOP = 12,
        //HTTOPLEFT = 13,
        //HTTOPRIGHT = 14,
        //HTBOTTOM = 15,
        //HTBOTTOMLEFT = 16,
        //HTBOTTOMRIGHT = 17;

        //    const int _ = 10; // you can rename this variable if you like

        //    Rectangle Top { get { return new Rectangle(0, 0, this.ClientSize.Width, _); } }
        //    Rectangle Left { get { return new Rectangle(0, 0, _, this.ClientSize.Height); } }
        //    Rectangle Bottom { get { return new Rectangle(0, this.ClientSize.Height - _, this.ClientSize.Width, _); } }
        //    Rectangle Right { get { return new Rectangle(this.ClientSize.Width - _, 0, _, this.ClientSize.Height); } }

        //    Rectangle TopLeft { get { return new Rectangle(0, 0, _, _); } }
        //    Rectangle TopRight { get { return new Rectangle(this.ClientSize.Width - _, 0, _, _); } }
        //    Rectangle BottomLeft { get { return new Rectangle(0, this.ClientSize.Height - _, _, _); } }
        //    Rectangle BottomRight { get { return new Rectangle(this.ClientSize.Width - _, this.ClientSize.Height - _, _, _); } }


        //    private const int cGrip = 16;      // Grip size
        //    private const int cCaption = 32;   // Caption bar height;

        //    protected override void WndProc(ref System.Windows.Forms.Message message)
        //    {
        //        base.WndProc(ref message);

        //        if (message.Msg == 0x84) // WM_NCHITTEST
        //        {
        //            Point pos = new Point(message.LParam.ToInt32());
        //            pos = this.PointToClient(pos);
        //            if (pos.Y < cCaption && pos.Y > 5)
        //            {
        //                message.Result = (IntPtr)2;  // HTCAPTION
        //                return;
        //            }

        //            var cursor = this.PointToClient(Cursor.Position);

        //            if (TopLeft.Contains(cursor)) message.Result = (IntPtr)HTTOPLEFT;
        //            else if (TopRight.Contains(cursor)) message.Result = (IntPtr)HTTOPRIGHT;
        //            else if (BottomLeft.Contains(cursor)) message.Result = (IntPtr)HTBOTTOMLEFT;
        //            else if (BottomRight.Contains(cursor)) message.Result = (IntPtr)HTBOTTOMRIGHT;

        //            else if (Top.Contains(cursor)) message.Result = (IntPtr)HTTOP;
        //            else if (Left.Contains(cursor)) message.Result = (IntPtr)HTLEFT;
        //            else if (Right.Contains(cursor)) message.Result = (IntPtr)HTRIGHT;
        //            else if (Bottom.Contains(cursor)) message.Result = (IntPtr)HTBOTTOM;
        //        }
        //    }

        //***********************************************************
        //This gives us the ability to resize the borderless from any borders instead of just the lower right corner
        //protected void WndProc22(ref System.Windows.Forms.Message m)
        //{
        //    const int wmNcHitTest = 0x84;
        //    const int htLeft = 10;
        //    const int htRight = 11;
        //    const int htTop = 12;
        //    const int htTopLeft = 13;
        //    const int htTopRight = 14;
        //    const int htBottom = 15;
        //    const int htBottomLeft = 16;
        //    const int htBottomRight = 17;

        //    if (WindowState != FormWindowState.Maximized)
        //    {
        //        if (m.Msg == wmNcHitTest)
        //        {
        //            int x = (int)(m.LParam.ToInt64() & 0xFFFF);
        //            int y = (int)((m.LParam.ToInt64() & 0xFFFF0000) >> 16);
        //            Point pt = PointToClient(new Point(x, y));
        //            Size clientSize = ClientSize;
        //            ///allow resize on the lower right corner
        //            if (pt.X >= clientSize.Width - 16 && pt.Y >= clientSize.Height - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htBottomLeft : htBottomRight);
        //                return;
        //            }
        //            ///allow resize on the lower left corner
        //            if (pt.X <= 16 && pt.Y >= clientSize.Height - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htBottomRight : htBottomLeft);
        //                return;
        //            }
        //            ///allow resize on the upper right corner
        //            if (pt.X <= 16 && pt.Y <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htTopRight : htTopLeft);
        //                return;
        //            }
        //            ///allow resize on the upper left corner
        //            if (pt.X >= clientSize.Width - 16 && pt.Y <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htTopLeft : htTopRight);
        //                return;
        //            }
        //            ///allow resize on the top border
        //            if (pt.Y <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htTop);
        //                return;
        //            }
        //            ///allow resize on the bottom border
        //            if (pt.Y >= clientSize.Height - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htBottom);
        //                return;
        //            }
        //            ///allow resize on the left border
        //            if (pt.X <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htLeft);
        //                return;
        //            }
        //            ///allow resize on the right border
        //            if (pt.X >= clientSize.Width - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htRight);
        //                return;
        //            }
        //        }
        //    }

        //    base.WndProc(ref m);
        //}

        IntPtr hWndNextWindow;

        public void StartClipboardMonitoring()
        {
            Log.Debug("Starting clipboard monitoring");
            hWndNextWindow = SetClipboardViewer(this.Handle);
        }

        public void StopClipboardMonitoring()
        {
            Log.Debug("Stopping clipboard monitoring");
            ChangeClipboardChain(this.Handle, hWndNextWindow);
        }

        public string GetClipboardText()
        {
            try
            {
                var text = Clipboard.GetText();
                if (!string.IsNullOrEmpty(text))
                {
                    return text;
                }
            }
            catch { }
            return null;
        }

        protected override void WndProc(ref System.Windows.Forms.Message m)
        {
            switch (m.Msg)
            {
                //case (0x0001): // WM_CREATE
                //    hWndNextWindow = SetClipboardViewer(this.Handle);
                //    break;
                case (0x0002): // WM_DESTROY
                    ChangeClipboardChain(this.Handle, hWndNextWindow);
                    break;
                case (0x030D): // WM_CHANGECBCHAIN
                    if (m.WParam == hWndNextWindow)
                        hWndNextWindow = m.LParam;
                    else if (hWndNextWindow != IntPtr.Zero)
                        SendMessage(hWndNextWindow, m.Msg, m.WParam, m.LParam);
                    break;
                case (0x0308): // WM_DRAWCLIPBOARD
                    {
                        OnClipboardChanged();
                    }
                    SendMessage(hWndNextWindow, m.Msg, m.WParam, m.LParam);
                    break;
            }

            base.WndProc(ref m);
        }

        //protected override void OnShown(EventArgs e)
        //{
        //    base.OnShown(e);
        //    if (Config.Instance.MonitorClipboard)
        //    {
        //        StartClipboardMonitoring();
        //    }
        //}

        private void OnClipboardChanged()
        {
            Log.Debug("Clipboard changed");
            this.ClipboardChanged?.Invoke(this, EventArgs.Empty);
        }

        public INewDownloadDialogSkeleton CreateNewDownloadDialog(bool empty)
        {
            var newDownloadDialogWin32 = new NewDownloadDialogView(empty);
            return newDownloadDialogWin32;
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            var newVideoDialogWin32 = new NewVideoDownloadDialogView();
            return newVideoDialogWin32;
        }

        //public void ShowNewDownloadDialog(Core.Lib.Common.Message message)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(new Action(() =>
        //        {
        //            NewDownloadDialogHelper.CreateAndShowDialog(this.App, this, this.CreateNewDownloadDialog(false), message);
        //        }));
        //    }
        //    else
        //    {
        //        NewDownloadDialogHelper.CreateAndShowDialog(this.App, this, this.CreateNewDownloadDialog(false), message);
        //    }
        //}

        //public void ShowVideoDownloadDialog(string videoId, string name, long size)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(new Action(() =>
        //        {
        //            NewVideoDownloadDialogHelper.ShowVideoDownloadDialog(this.App, this, this.CreateNewVideoDialog(), videoId, name, size);
        //        }));
        //    }
        //    else
        //    {
        //        NewVideoDownloadDialogHelper.ShowVideoDownloadDialog(this.App, this, this.CreateNewVideoDialog(), videoId, name, size);
        //    }

        //}



        //public void InvokeForm(Action callback)
        //{
        //    if (this.InvokeRequired)
        //    {
        //        this.BeginInvoke(callback);
        //    }
        //    else
        //    {
        //        callback();
        //    }
        //}

        //protected void WndProc33(ref System.Windows.Forms.Message m)
        //{
        //    const int wmNcHitTest = 0x84;
        //    const int htLeft = 10;
        //    const int htRight = 11;
        //    const int htTop = 12;
        //    const int htTopLeft = 13;
        //    const int htTopRight = 14;
        //    const int htBottom = 15;
        //    const int htBottomLeft = 16;
        //    const int htBottomRight = 17;

        //    if (WindowState != FormWindowState.Maximized)
        //    {
        //        if (m.Msg == wmNcHitTest)
        //        {
        //            int x = (int)(m.LParam.ToInt64() & 0xFFFF);
        //            int y = (int)((m.LParam.ToInt64() & 0xFFFF0000) >> 16);
        //            Point pt = PointToClient(new Point(x, y));
        //            Size clientSize = ClientSize;
        //            ///allow resize on the lower right corner
        //            if (pt.X >= clientSize.Width - 16 && pt.Y >= clientSize.Height - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htBottomLeft : htBottomRight);
        //                return;
        //            }
        //            ///allow resize on the lower left corner
        //            if (pt.X <= 16 && pt.Y >= clientSize.Height - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htBottomRight : htBottomLeft);
        //                return;
        //            }
        //            ///allow resize on the upper right corner
        //            if (pt.X <= 16 && pt.Y <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htTopRight : htTopLeft);
        //                return;
        //            }
        //            ///allow resize on the upper left corner
        //            if (pt.X >= clientSize.Width - 16 && pt.Y <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(IsMirrored ? htTopLeft : htTopRight);
        //                return;
        //            }
        //            ///allow resize on the top border
        //            if (pt.Y <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htTop);
        //                return;
        //            }
        //            ///allow resize on the bottom border
        //            if (pt.Y >= clientSize.Height - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htBottom);
        //                return;
        //            }
        //            ///allow resize on the left border
        //            if (pt.X <= 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htLeft);
        //                return;
        //            }
        //            ///allow resize on the right border
        //            if (pt.X >= clientSize.Width - 16 && clientSize.Height >= 16)
        //            {
        //                m.Result = (IntPtr)(htRight);
        //                return;
        //            }
        //        }
        //    }

        //    base.WndProc(ref m);
        //}

        private void CreateTrayIcon()
        {
            exitMenuItem.Click += ExitApp;
            //var ctxMenu = new ContextMenuStrip();
            //var mExit = new ToolStripMenuItem("Exit", null, ExitApp);
            //mExit.Padding = new Padding(10, 10, 10, 10);
            //ctxMenu.Items.Add(mExit);
            ////ctxMenu.Items.Add("Exit", null, ExitApp);

            // Initialize Tray Icon
            trayIcon = new NotifyIcon()
            {
                //Icon = Resources.AppIcon,
                ContextMenuStrip = ctxMenuNotifyIcon,
                Visible = true,
                Icon = this.Icon,
                Text = "XDM"
            };

            trayIcon.DoubleClick += (a, b) =>
            {
                this.WindowState = FormWindowState.Normal;
                this.Visible = true;
                this.BringToFront();
                if (!this.IsHandleCreated)
                {
                    this.CreateHandle();
                }
                SetForegroundWindow(this.Handle);
            };
        }

        void ExitApp(object sender, EventArgs e)
        {
            // Hide tray icon, otherwise it will remain shown until user mouses over it
            trayIcon.Visible = false;
            GlobalFontCollection.Dispose();
            Application.Exit();
            Environment.Exit(0);
        }

        protected override void OnClosing(CancelEventArgs e)
        {
            e.Cancel = true;
            this.Visible = false;
        }

        public IProgressWindow CreateProgressWindow(string downloadId, IApp app, IAppUI appUI)
        {
            var prgWin = new ProgressWindow
            {
                DownloadId = downloadId,
                App = app,
                AppUI = appUI
            };
            return prgWin;
        }

        private void button14_Click(object sender, EventArgs e)
        {

        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog(IApp app)
        {
            var dwnCmpldDlg = new DownloadCompleteDialog
            {
                App = app,
            };

            return dwnCmpldDlg;
        }

        //public void ShowDownloadCompleteDialog(string file, string folder)
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(new Action(() =>
        //        {
        //            DownloadCompleteDialogHelper.ShowDialog(this.App, CreateDownloadCompleteDialog(), file, folder);
        //        }));
        //    }
        //    else
        //    {
        //        DownloadCompleteDialogHelper.ShowDialog(this.App, this.CreateDownloadCompleteDialog(), file, folder);
        //    }
        //}

        //private void OpenSelectedFile()
        //{
        //    if (dgCompletedList.SelectedRows.Count > 0)
        //    {
        //        var row = dgCompletedList.SelectedRows[0];
        //        var ent = this.downloadsDB.FinishedItems[row.Index];// row.Cells[1].Value as FinishedDownloadEntry;
        //        if (!string.IsNullOrEmpty(ent.TargetDir))
        //        {
        //            var file = Path.Combine(ent.TargetDir, ent.Name);
        //            Log.Information("Open: " + file);
        //            if (!Helpers.OpenFile(file))
        //            {
        //                MessageBox.Show("Could not open file, it is either deleted or moved to a different location");
        //            }
        //            return;
        //        }
        //        else
        //        {
        //            Log.Error("Path is null");
        //        }
        //    }
        //    MessageBox.Show("Please select a file to open");
        //}

        //private void OpenSelectedFolder()
        //{
        //    if (dgCompletedList.SelectedRows.Count > 0)
        //    {
        //        var row = dgCompletedList.SelectedRows[0];
        //        var ent = this.downloadsDB.FinishedItems[row.Index];// row.Cells[1].Value as FinishedDownloadEntry;
        //        //var file = Path.Combine(ent.TargetDir, ent.Name);
        //        Log.Information("Open folder: " + ent.TargetDir);
        //        if (!Helpers.OpenFolder(ent.TargetDir, ent.Name))
        //        {
        //            MessageBox.Show("Could not open folder, it is either deleted or moved to a different location");
        //        }
        //        return;
        //    }
        //    MessageBox.Show("Please select a item to open");
        //}



        public string GetUrlFromClipboard()
        {
            var iData = Clipboard.GetDataObject();
            if (iData.GetDataPresent(DataFormats.Text))
            {
                var text = (String)iData.GetData(DataFormats.Text);
                if (XDM.Core.Lib.Util.Helpers.IsUriValid(text))
                {
                    return text;
                }
            }
            return null;
        }


        private void OnMouseDown(object sender, DataGridViewCellMouseEventArgs e)
        {
            if (e.ColumnIndex != -1 && e.RowIndex != -1 && e.Button == System.Windows.Forms.MouseButtons.Right)
            {
                DataGridViewCell c = (sender as DataGridView)[e.ColumnIndex, e.RowIndex];
                if (!c.Selected)
                {
                    c.DataGridView.ClearSelection();
                    c.DataGridView.CurrentCell = c;
                    c.Selected = true;
                }
            }
        }

        private void SetupContextMenu()
        {
            dgActiveList.CellMouseDown += OnMouseDown;

            dgCompletedList.CellMouseDown += OnMouseDown;

            dgActiveList.RowContextMenuStripNeeded += (sender, args) =>
            {
                args.ContextMenuStrip = this.ctxMenuActiveList;
            };

            dgCompletedList.RowContextMenuStripNeeded += (sender, args) =>
            {
                args.ContextMenuStrip = this.ctxMenuCompletedList;
            };

            ctxMenuActiveList.Opening += (s, e) =>
            {
                this.InProgressContextMenuOpening?.Invoke(s, e);
                //foreach (ToolStripItem item in ctxMenuActiveList.Items)
                //{
                //    item.Enabled = false;
                //}
                //deleteToolStripMenuItem.Enabled = true;
                //scheduleToolStripMenuItem.Enabled = true;
                //var selectedRows = dgActiveList.SelectedRows;
                //if (selectedRows.Count > 1)
                //{
                //    pauseToolStripMenuItem.Enabled = true;
                //    resumeToolStripMenuItem.Enabled = true;
                //    showProgressToolStripMenuItem.Enabled = true;
                //    copyURLToolStripMenuItem.Enabled = true;
                //}
                //else if (selectedRows.Count == 1)
                //{
                //    showProgressToolStripMenuItem.Enabled = true;
                //    copyURLToolStripMenuItem.Enabled = true;
                //    saveAsToolStripMenuItem1.Enabled = true;
                //    refreshLinkToolStripMenuItem.Enabled = true;
                //    previewToolStripMenuItem.Enabled = true;
                //    showProgressToolStripMenuItem.Enabled = true;
                //    copyURLToolStripMenuItem.Enabled = true;
                //    propertiesToolStripMenuItem.Enabled = true;

                //    var ent = GetSelectedInProgressEntry();//selectedRows[0].Cells[1].Value as InProgressDownloadEntry;
                //    if (ent == null) return;
                //    var isActive = App.IsDownloadActive(ent.Id);
                //    Log.Information("Selected item active: " + isActive);
                //    if (isActive)
                //    {
                //        pauseToolStripMenuItem.Enabled = true;
                //    }
                //    else
                //    {
                //        resumeToolStripMenuItem.Enabled = true;
                //        restartToolStripMenuItem.Enabled = true;
                //    }
                //}
            };

            ctxMenuCompletedList.Opening += (s, e) =>
            {
                this.FinishedContextMenuOpening?.Invoke(s, e);
                //foreach (ToolStripItem item in ctxMenuCompletedList.Items)
                //{
                //    item.Enabled = false;
                //}
                //deleteDownloadsToolStripMenuItem.Enabled = true;
                //var selectedRows = dgCompletedList.SelectedRows;
                //if (selectedRows.Count == 1)
                //{
                //    foreach (ToolStripItem item in ctxMenuCompletedList.Items)
                //    {
                //        item.Enabled = true;
                //    }
                //}
            };
        }

        //private void refreshLinkToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    var selected = GetSelectedInProgressEntry();
        //    if (selected == null) return;
        //    using var dlg = new Win32LinkRefreshWindow();
        //    LinkRefreshDialogHelper.RefreshLink(selected, App, dlg);
        //}

        public void ShowRefreshLinkDialog(InProgressDownloadEntry entry, IApp app)
        {
            using var dlg = new LinkRefreshWindow();
            LinkRefreshDialogHelper.RefreshLink(entry, app, dlg);
        }

        private void moveToQueueToolStripMenuItem_Click(object sender, EventArgs e)
        {
            MoveToQueueClicked?.Invoke(this, EventArgs.Empty);
        }

        //private void showProgressToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    var selected = GetSelectedInProgressEntry();
        //    if (selected != null)
        //    {
        //        App?.ShowProgressWindow(selected.Id);
        //    }
        //}

        //private void copyURLToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    var entry = GetSelectedInProgressEntry();
        //    if (entry != null)
        //    {
        //        CopyURL(entry);
        //    }
        //}

        //private void propertiesToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    ShowSeletectedItemProperties();
        //}

        //private void openToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    OpenSelectedFile();
        //}

        //private void openFolderToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    OpenSelectedFolder();
        //}

        //private void deleteDownloadsToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    DeleteDownloads(dgState.Rows[0].Selected);
        //}

        //private void copyURLToolStripMenuItem1_Click(object sender, EventArgs e)
        //{
        //    var entry = GetSelectedFinishedEntry();
        //    if (entry != null)
        //    {
        //        CopyURL(entry);
        //    }
        //}

        //private void CopyURL(BaseDownloadEntry entry)
        //{
        //    var url = App.GetPrimaryUrl(entry);
        //    if (url != null)
        //    {
        //        SetClipboardText(url);
        //    }
        //}

        public void SetClipboardText(string text)
        {
            Clipboard.SetText(text);
        }

        public void SetClipboardFile(string file)
        {
            Clipboard.SetFileDropList(new StringCollection
            {
                file
            });
        }


        //private void copyFileToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    var entry = GetSelectedFinishedEntry();
        //    if (entry != null)
        //    {
        //        var file = Path.Combine(entry.TargetDir, entry.Name);
        //        if (File.Exists(file))
        //        {
        //            Clipboard.SetFileDropList(new StringCollection
        //            {
        //                file
        //            });
        //        }
        //        else
        //        {
        //            MessageBox.Show("File does not exist");
        //        }
        //    }
        //}

        //private void propertiesToolStripMenuItem1_Click(object sender, EventArgs e)
        //{
        //    ShowSeletectedItemProperties();
        //}

        //private void restartToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    var selectedItem = GetSelectedInProgressEntry();
        //    if (selectedItem == null) return;
        //    App.RestartDownload(selectedItem);
        //}

        //private void redownloadToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    var selectedItem = GetSelectedFinishedEntry();
        //    if (selectedItem == null) return;
        //    App.RestartDownload(selectedItem);
        //}

        //private void pauseToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    StopSelectedDownloads();
        //}

        //private void resumeToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    ResumeDownloads();
        //}

        //private void deleteToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    DeleteDownloads(dgState.Rows[0].Selected);
        //}

        //private void saveAsToolStripMenuItem1_Click(object sender, EventArgs e)
        //{
        //    var item = GetSelectedInProgressEntry();
        //    if (item == null) return;
        //    using var fc = new SaveFileDialog();
        //    fc.FileName = Path.Combine(item.TargetDir ?? Helpers.GetDownloadFolderByFileName(item.Name), item.Name);
        //    if (fc.ShowDialog(this) != DialogResult.OK)
        //    {
        //        return;
        //    }
        //    var file = fc.FileName;
        //    Log.Information("folder: " + Path.GetDirectoryName(file) + " file: " + Path.GetFileName(file));
        //    App.RenameDownload(item.Id, Path.GetDirectoryName(file), Path.GetFileName(file));
        //}

        //public void RenameFileOnUI(string id, string folder, string file)
        //{
        //    RunOnUIThread(() =>
        //    {
        //        var downloadEntry = this.downloadsDB.InProgressItems.FindDownload(id); //InProgressDict[id]["NameCol"] as InProgressDownloadEntry;
        //        if (downloadEntry == null) return;
        //        if (file != null)
        //        {
        //            downloadEntry.Name = file;
        //        }
        //        if (folder != null)
        //        {
        //            downloadEntry.DownloadEntry.TargetDir = folder;
        //        }
        //        this.downloadsDB.InProgressItems.Save();
        //        //InProgressDict[id].SetField("NameCol", downloadEntry);
        //        //App.SaveInProgressList(GetInprogressList());
        //    });
        //}

        //private void ShowSeletectedItemProperties()
        //{
        //    if ((dgState.Rows[0].Selected && dgActiveList.SelectedRows.Count == 0) ||
        //        (dgState.Rows[1].Selected && dgCompletedList.SelectedRows.Count == 0))
        //    {
        //        return;
        //    }

        //    var inProgressSelected = dgState.Rows[0].Selected;
        //    BaseDownloadEntry? ent = inProgressSelected ? GetSelectedInProgressEntry() : GetSelectedFinishedEntry();
        //    if (ent == null) return;
        //    ShortState? state = null;
        //    try
        //    {
        //        var stateFile = Path.Combine(Config.DataDir, ent.Id + ".state");
        //        state = JsonConvert.DeserializeObject<ShortState>(
        //            File.ReadAllText(stateFile),
        //            new JsonSerializerSettings
        //            {
        //                MissingMemberHandling = MissingMemberHandling.Ignore,
        //            });
        //    }
        //    catch { }

        //    var propsDlg = new Win32PropertiesWindow
        //    {
        //        FileName = ent.Name,
        //        Folder = ent.TargetDir ?? XDM.Core.Lib.Util.Helpers.GetDownloadFolderByFileName(ent.Name),
        //        Address = ent.PrimaryUrl,
        //        FileSize = XDM.Core.Lib.Util.Helpers.FormatSize(ent.Size),
        //        DateAdded = ent.DateAdded.ToLongDateString() + " " + ent.DateAdded.ToLongTimeString(),
        //        DownloadType = ent.DownloadType,
        //        Referer = ent.RefererUrl,
        //        Cookies = state?.Cookies ?? state?.Cookies1 ?? new Dictionary<string, string>(),
        //        Headers = state?.Headers ?? state?.Headers1 ?? new Dictionary<string, List<string>>(),
        //    };
        //    propsDlg.Visible = true;
        //}

        public void ShowPropertiesDialog(BaseDownloadEntry ent, ShortState? state)
        {
            var propsDlg = new PropertiesWindow
            {
                FileName = ent.Name,
                Folder = ent.TargetDir ?? Helpers.GetDownloadFolderByFileName(ent.Name),
                Address = ent.PrimaryUrl,
                FileSize = Helpers.FormatSize(ent.Size),
                DateAdded = ent.DateAdded.ToLongDateString() + " " + ent.DateAdded.ToLongTimeString(),
                DownloadType = ent.DownloadType,
                Referer = ent.RefererUrl,
                Cookies = state?.Cookies ?? state?.Cookies1 ?? new Dictionary<string, string>(),
                Headers = state?.Headers ?? state?.Headers1 ?? new Dictionary<string, List<string>>(),
            };
            propsDlg.Visible = true;
        }

        //private void SetupMainMenu()
        //{
        //    //ctxMainMenu.Left = btnSettings.Left;
        //    //ctxMainMenu.Top = btnSettings.Bottom;
        //    //btnSettings.ContextMenuStrip = ctxMainMenu;
        //    //ctxMainMenu.Show()

        //    //settingsToolStripMenuItem1.Click += (a, b) =>
        //    //{
        //    //    using var dlg = new Win32SettingsWindow();
        //    //    dlg.App = App;
        //    //    dlg.ShowDialog(this);
        //    //};
        //}

        //private void SetupDownloadMenu()
        //{
        //    newDownloadToolStripMenuItem.Click += (a, b) =>
        //    {
        //        this.NewDownloadClicked?.Invoke(a, b);
        //    };
        //}

        //private void UpdateInprogressToolbarButtons()
        //{
        //    if (dgState.SelectedRows.Count == 1 &&
        //        dgState.SelectedRows[0].Index == 0 &&
        //        dgActiveList.SelectedRows.Count == 1)
        //    {
        //        var ent = GetSelectedInProgressEntry();//dgActiveList.SelectedRows[0].Cells[1].Value as InProgressDownloadEntry;
        //        if (ent == null) return;
        //        var isActive = ent.Status == DownloadStatus.Downloading;

        //        DisableButton(btnPause);
        //        DisableButton(btnResume);

        //        Log.Information("Selected item active: " + isActive);

        //        if (isActive)
        //        {
        //            EnableButton(btnPause);
        //        }
        //        else
        //        {
        //            EnableButton(btnResume);
        //        }
        //    }
        //}

        //public void UpdateUIButtons()
        //{
        //    if (InvokeRequired)
        //    {
        //        BeginInvoke(this.downloadStateChangeAction);
        //    }
        //    else
        //    {
        //        lock (this)
        //        {
        //            this.downloadStateChangeAction();
        //        }
        //    }
        //}



        //protected override void OnHandleCreated(EventArgs e)
        //{
        //    try
        //    {
        //        DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
        //        DarkModeHelper.AllowDarkModeForWindow(this.Handle, 1);
        //        DarkModeHelper.SetWindowTheme(this.Handle, "DarkMode_Explorer", null);
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Debug(ex, ex.Message);
        //    }
        //    base.OnHandleCreated(e);
        //}

        //protected override void OnShown(EventArgs e)
        //{
        //    //AllowDarkModeForWindow(this.dgCompletedList.Handle, 1);
        //    SetWindowTheme(this.dgCompletedList.Handle, "DarkMode_Explorer", null);
        //    base.OnShown(e);
        //}


        private void exitToolStripMenuItem_Click(object sender, EventArgs e)
        {
            GlobalFontCollection.Dispose();
            Environment.Exit(0);
        }

        public void ShowYoutubeDLDialog(IAppUI appUI, IApp app)
        {
            var dlg = new VideoDownloaderWindow(fa10Font, app, appUI);
            dlg.Visible = true;
        }

        private void videoDownloadToolStripMenuItem_Click(object sender, EventArgs e)
        {
            YoutubeDLDownloadClicked?.Invoke(sender, e);
        }


        public DownloadSchedule? ShowSchedulerDialog(DownloadSchedule schedule)
        {
            using var dlg = new SchedulerWindow();
            dlg.Schedule = schedule;
            if (dlg.ShowDialog(this) == DialogResult.OK)
            {
                return dlg.Schedule;
            }
            return null;
        }

        //private void scheduleToolStripMenuItem_Click(object sender, EventArgs e)
        //{
        //    using var dlg = new Win32SchedulerWindow();
        //    if (dgActiveList.SelectedRows.Count == 1)
        //    {
        //        var list = this.GetInProgressSelectedItems();//dgActiveList.SelectedRows[0].Cells[1].Value as InProgressDownloadEntry;
        //        if (list.Count == 0) return;
        //        dlg.Schedule = list[0].DownloadEntry.Schedule.GetValueOrDefault();
        //        if (dlg.ShowDialog(this) == DialogResult.OK)
        //        {
        //            var schedule = dlg.Schedule;
        //            foreach (var row in list)
        //            {
        //                var ent = row.DownloadEntry;
        //                ent.Schedule = schedule;
        //            }
        //            this.downloadsDB.InProgressItems.Save();
        //        }
        //    }
        //}

        //private static bool IsWindows10OrGreater(int build = -1)
        //{
        //    return Environment.OSVersion.Version.Major >= 10 && Environment.OSVersion.Version.Build >= build;
        //}

        //private Image CreateFileTypeImageFromFont()
        //{
        //    var font = new Font(fcRemixIcon.Families[0], 12);
        //    var img = CreateFileIcon(font,
        //             ((char)Int32.Parse("eceb", System.Globalization.NumberStyles.HexNumber)).ToString(),
        //             Color.Orange, Color.White, 5);
        //    return img;
        //}

        //private Image CreateFileTypeImageFromFont(string iconText, Color backColor)
        //{
        //    var font = new Font(fcFontAwesome.Families[0], 12);
        //    var img = CreateFileIcon(font,
        //             iconText,
        //             backColor, Color.White, 5);
        //    return img;
        //}

        //private Bitmap Create(Font font, string text, int size)
        //{
        //    using var g1 = this.CreateGraphics();
        //    var sizeF = g1.MeasureString(text, font);
        //    //var bmpDimension = LogicalToDeviceUnits(size);
        //    //Console.WriteLine(nameof(bmpDimension) + ": " + bmpDimension + " " + nameof(size) + ": " + size);

        //    var width = (int)sizeF.Width;
        //    var height = (int)sizeF.Height;

        //    var bitmap = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        //    using Graphics g = Graphics.FromImage(bitmap);
        //    g.SmoothingMode = SmoothingMode.AntiAlias;
        //    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        //    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        //    g.TextRenderingHint = TextRenderingHint.AntiAlias;

        //    TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
        //        TextFormatFlags.VerticalCenter;
        //    TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), Color.Gray, flags);
        //    return bitmap;
        //}

        private Bitmap CreateFileIcon(Font font, string text, Color backColor, Color foreColor, int padding)
        {
            using var g1 = this.CreateGraphics();
            var sizeF = g1.MeasureString(text, font);

            var width = (int)sizeF.Width + LogicalToDeviceUnits(padding);
            var height = (int)sizeF.Height + LogicalToDeviceUnits(padding);

            var bitmap = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
            using Graphics g = Graphics.FromImage(bitmap);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;

            using var bgBrush = new SolidBrush(backColor);
            using var fgBrush = new SolidBrush(foreColor);
            g.FillRectangle(bgBrush, 0, 0, width, height);

            StringFormat sf = new StringFormat();
            sf.LineAlignment = StringAlignment.Center;
            sf.Alignment = StringAlignment.Center;
            sf.FormatFlags = StringFormatFlags.NoWrap | StringFormatFlags.FitBlackBox | StringFormatFlags.FitBlackBox;

            g.DrawString(text, font, fgBrush, (sizeF.Width + LogicalToDeviceUnits(padding)) / 2 - sizeF.Width / 2, (sizeF.Height + LogicalToDeviceUnits(padding)) / 2 - sizeF.Height / 2);

            //TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
            //    TextFormatFlags.VerticalCenter| TextFormatFlags.;
            //g.DrawString(text, font, fgBrush, new Rectangle(0, 0, bitmap.Width, bitmap.Height), sf);
            //TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), foreColor, flags);
            return bitmap;
        }

        private Bitmap CreateToolbarIcon(Font font, string text, Color foreColor, int paddingW = 0, int paddingH = 0)
        {
            using var g1 = this.CreateGraphics();
            var sizeF = g1.MeasureString(text, font);

            var width = (int)sizeF.Width + LogicalToDeviceUnits(paddingW);
            var height = (int)sizeF.Height + LogicalToDeviceUnits(paddingH);

            var bitmap = new Bitmap(width, height, System.Drawing.Imaging.PixelFormat.Format32bppPArgb);
            using Graphics g = Graphics.FromImage(bitmap);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;

            //using var bgBrush = new SolidBrush(backColor);
            using var fgBrush = new SolidBrush(foreColor);
            //g.FillRectangle(bgBrush, 0, 0, width, height);

            StringFormat sf = new StringFormat();
            sf.LineAlignment = StringAlignment.Center;
            sf.Alignment = StringAlignment.Center;
            sf.FormatFlags = StringFormatFlags.NoWrap | StringFormatFlags.FitBlackBox | StringFormatFlags.FitBlackBox;

            g.DrawString(text, font, fgBrush, (sizeF.Width + LogicalToDeviceUnits(paddingW)) / 2 - sizeF.Width / 2, (sizeF.Height + LogicalToDeviceUnits(paddingH)) / 2 - sizeF.Height / 2);

            //TextFormatFlags flags = TextFormatFlags.HorizontalCenter |
            //    TextFormatFlags.VerticalCenter| TextFormatFlags.;
            //g.DrawString(text, font, fgBrush, new Rectangle(0, 0, bitmap.Width, bitmap.Height), sf);
            //TextRenderer.DrawText(g, text, font, new Rectangle(0, 0, bitmap.Width, bitmap.Height), foreColor, flags);
            return bitmap;
        }

        //public InProgressDownloadEntry? GetInProgressDownloadEntry(string downloadId) =>
        //    this.downloadsDB.InProgressItems.FindDownload(downloadId)?.DownloadEntry;

        //public InProgressDownloadEntryBinder? GetInProgressDownloadEntryBinder(string downloadId) =>
        //    this.downloadsDB.InProgressItems.FindDownload(downloadId);

        public void RunOnUIThread(Action action)
        {
            if (InvokeRequired)
            {
                BeginInvoke(action);
            }
            else
            {
                action.Invoke();
            }
        }

        public void ShowBatchDownloadWindow(IApp app, IAppUI appUi)
        {
            using var dlg = new BatchDownloadWindow(app, appUi);
            dlg.ShowDialog(this);
        }

        private void batchDownloadToolStripMenuItem_Click(object sender, EventArgs e)
        {
            BatchDownloadClicked?.Invoke(sender, e);
        }

        //public void SetDownloadStatusWaiting(string id)
        //{
        //    var ent = GetInProgressDownloadEntryBinder(id);
        //    if (ent != null)
        //    {
        //        ent.Status = DownloadStatus.Waiting;
        //    }
        //}

        //public IEnumerable<InProgressDownloadEntry> GetEnumerable()
        //{
        //    return this.downloadsDB.InProgressItems.GetDownloadEntries();
        //}

        //private InProgressDownloadEntry? GetSelectedInProgressEntry()
        //{
        //    if (dgActiveList.SelectedRows.Count < 1) return null;
        //    return this.downloadsDB.InProgressItems[dgActiveList.SelectedRows[0].Index].DownloadEntry;//  dgActiveList.SelectedRows[0].Cells[1].Value as InProgressDownloadEntry;
        //}

        //private FinishedDownloadEntry? GetSelectedFinishedEntry()
        //{
        //    if (dgCompletedList.SelectedRows.Count < 1) return null;
        //    return this.downloadsDB.FinishedItems[dgCompletedList.SelectedRows[0].Index];
        //    //return dgCompletedList.SelectedRows[0].Cells[1].Value as FinishedDownloadEntry;
        //}

        //private void ApplyFilter()
        //{
        //    ApplyFilter(false);
        //}

        //private void ClearFilter()
        //{
        //    ApplyFilter(true);
        //}

        //private void ApplyFilter(bool clear)
        //{
        //    //var index = dgCategories.SelectedRows[0].Index;
        //    //var category = (Category)dgCategories.Rows[index].Cells[1].Value;
        //    //Log.Debug("Selected category: " + category);

        //    //dgActiveList.SuspendLayout();
        //    //dgCompletedList.SuspendLayout();

        //    //var filter = new StringBuilder();

        //    //if (!clear)
        //    //{
        //    //    if (textBox1.Text.Length > 0)
        //    //    {
        //    //        filter.Append($"CONVERT( NameCol, System.String) LIKE '%{textBox1.Text}%'");
        //    //    }
        //    //    if (category.FileExtensions.Count > 0)
        //    //    {
        //    //        if (filter.Length > 0)
        //    //        {
        //    //            filter.Append(" AND ");
        //    //        }
        //    //        filter.Append($"ImgCol IN ({string.Join(",", category.FileExtensions.Select(x => "'" + x + "'"))})");
        //    //    }
        //    //}

        //    ////var filter = category.FileExtensions.Count > 0 ? $"ImgCol IN ({string.Join(",", category.FileExtensions.Select(x => "'" + x + "'"))})" : string.Empty;
        //    //Log.Debug("Setting row filter: " + filter);
        //    //tabComplete.DefaultView.RowFilter = filter.ToString();
        //    ////this.categoryFilter = category;
        //    ////this.tabIncomplete.Clear();
        //    ////this.tabComplete.Clear();
        //    ////app.LoadDownloadList();

        //    //dgActiveList.ResumeLayout();
        //    //dgCompletedList.ResumeLayout();

        //    //dgCompletedList.ClearSelection();
        //    //if (dgCompletedList.Rows.Count > 0)
        //    //{
        //    //    dgCompletedList.FirstDisplayedScrollingRowIndex = 0;
        //    //}
        //}

        private void ApplyCategoryFilter()
        {
            if (IsInProgressViewSelected)
            {
                this.downloadsDB.InProgressItems.UpdateView(this.searchText);
                this.dgActiveList.SuspendLayout();
                this.dgActiveList.RowCount = 0;
                this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
                this.dgActiveList.Refresh();
                this.dgActiveList.ClearSelection();
                this.dgActiveList.ResumeLayout();
            }
            else
            {
                var index = dgCategories.SelectedRows[0].Index;
                var category = (Category)dgCategories.Rows[index].Cells[1].Value;
                Log.Debug("Updating view with category: " + category);
                this.downloadsDB.FinishedItems.UpdateView(this.searchText, category);
                this.dgCompletedList.SuspendLayout();
                this.dgCompletedList.RowCount = 0;
                this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
                this.dgCompletedList.Refresh();
                this.dgCompletedList.ClearSelection();
                this.dgCompletedList.ResumeLayout();
            }
            //Log.Debug("Selected category: " + category);

            //dgActiveList.SuspendLayout();
            //dgCompletedList.SuspendLayout();

            //var filter = new StringBuilder();
            //if (category.FileExtensions.Count > 0)
            //{
            //    filter.Append($"ImgCol IN ({string.Join(",", category.FileExtensions.Select(x => "'" + x + "'"))})");
            //}
            ////filter.Append(category.FileExtensions.Count > 0 ? $"ImgCol IN ({string.Join(",", category.FileExtensions.Select(x => "'" + x + "'"))})" : string.Empty);

            //if (!string.IsNullOrEmpty(searchText))
            //{
            //    if (filter.Length > 0)
            //    {
            //        filter.Append(" AND ");
            //        filter.Append($"CONVERT( NameCol, System.String) LIKE '%{searchText}%'");
            //    }
            //}

            //Log.Debug("Setting row filter: " + filter);
            //tabComplete.DefaultView.RowFilter = filter.ToString();
            ////this.categoryFilter = category;
            ////this.tabIncomplete.Clear();
            ////this.tabComplete.Clear();
            ////app.LoadDownloadList();

            //dgActiveList.ResumeLayout();
            //dgCompletedList.ResumeLayout();

            //dgCompletedList.ClearSelection();
            //if (dgCompletedList.Rows.Count > 0)
            //{
            //    dgCompletedList.FirstDisplayedScrollingRowIndex = 0;
            //}
        }

        private void ClearCategoryFilter()
        {
            if (IsInProgressViewSelected)
            {
                this.downloadsDB.InProgressItems.UpdateView(this.searchText);
                this.dgActiveList.RowCount = 0;
                this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
                this.dgActiveList.Refresh();
                this.dgActiveList.ClearSelection();
            }
            else
            {
                this.downloadsDB.FinishedItems.UpdateView(this.searchText, null);
                this.dgCompletedList.RowCount = 0;
                this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
                this.dgCompletedList.Refresh();
                this.dgCompletedList.ClearSelection();
            }
            //dgActiveList.SuspendLayout();
            //dgCompletedList.SuspendLayout();
            //Log.Debug("clearing row filter");

            //var filter = string.Empty;
            //if (!string.IsNullOrEmpty(searchText))
            //{
            //    filter = $"CONVERT( NameCol, System.String) LIKE '%{searchText}%'";
            //}
            //tabComplete.DefaultView.RowFilter = filter;

            ////this.categoryFilter = null;
            ////this.tabIncomplete.Clear();
            ////this.tabComplete.Clear();
            ////app.LoadDownloadList();

            //dgActiveList.ResumeLayout();
            //dgCompletedList.ResumeLayout();

            //dgCompletedList.ClearSelection();
            //if (dgCompletedList.Rows.Count > 0)
            //{
            //    dgCompletedList.FirstDisplayedScrollingRowIndex = 0;
            //}
        }

        private void LayoutMenuItems()
        {
            foreach (ContextMenuStrip menu in new[] { ctxMainMenu, ctxDownloadMenu, ctxMenuActiveList, ctxMenuCompletedList, ctxMenuNotifyIcon })
            {
                MenuHelper.FixHiDpiMargin(menu);
                //menu.ShowImageMargin = true;
                //foreach (var item in menu.Items)
                //{
                //    if (item is ToolStripMenuItem menuItem)
                //    {
                //        menuItem.DisplayStyle = ToolStripItemDisplayStyle.ImageAndText;
                //        menuItem.Image = bmp;
                //    }
                //}
            }
        }

        [DllImport("User32.dll", CharSet = CharSet.Auto)]
        public static extern IntPtr SetClipboardViewer(IntPtr hWndNewViewer);

        [DllImport("User32.dll", CharSet = CharSet.Auto)]
        public static extern bool ChangeClipboardChain(IntPtr hWndRemove, IntPtr hWndNewNext);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        public static extern int SendMessage(IntPtr hwnd, int wMsg, IntPtr wParam, IntPtr lParam);

        public void DeleteAllFinishedDownloads()
        {
            if (MessageBox.Show(this, "Remove all completed downloads?", "Confirm", MessageBoxButtons.YesNo) != DialogResult.Yes)
            {
                return;
            }
            downloadsDB.FinishedItems.Clear();
            dgCompletedList.RowCount = 0;
            dgCompletedList.Refresh();
        }

        private void removeFinishedToolStripMenuItem_Click(object sender, EventArgs e)
        {
            this.ClearAllFinishedClicked?.Invoke(sender, e);
        }

        public void ShowBrowserMonitoringDialog(IApp app)
        {
            ShowSettingsDialog(app, 0);
        }

        private void browserMonitoringToolStripMenuItem_Click(object sender, EventArgs e)
        {
            BrowserMonitoringSettingsClicked?.Invoke(sender, e);
        }

        public void ShowSettingsDialog(IApp app, int page = 1)
        {
            using var dlg = new SettingsWindow(app, page);
            dlg.ShowDialog(this);
        }

        private void settingsToolStripMenuItem1_Click(object sender, EventArgs e)
        {
            SettingsClicked?.Invoke(sender, e);
            //using var dlg = new Win32SettingsWindow(fcRemixIcon, App);
            //dlg.ShowDialog(this);
            //ShowParallalism();
        }

        public void UpdateParallalismLabel()
        {
            //var maxParallalism = Config.Instance.MaxParallelDownloads;
            //btnParallel.Text = "Queue and scheduler";
        }

        private void aboutXDMToolStripMenuItem_Click(object sender, EventArgs e)
        {
            using var aboutBox = new AboutBox();
            aboutBox.ShowDialog(this);
        }

        private void btnParallel_Click(object sender, EventArgs e)
        {
            this.SchedulerClicked?.Invoke(sender, e);
            //using var dlg = new Win32SettingsWindow(fcRemixIcon, App, 1);
            //dlg.ShowDialog(this);
        }

        // defined in winuser.h
        const int WM_DRAWCLIPBOARD = 0x308;

        public IUpdaterUI CreateUpdateUIDialog(IAppUI ui)
        {
            return new UpdaterWindow(ui);
        }

        private void btnHelp_Click(object sender, EventArgs e)
        {
            if (btnHelp.Tag != null)
            {
                UpdateClicked?.Invoke(sender, e);
            }
            else
            {
                HelpClicked?.Invoke(sender, e);
            }
            //if (btnHelp.Tag != null && App.Updates?.Count > 0)
            //{
            //    if (App.IsAppUpdateAvailable)
            //    {
            //        Helpers.OpenBrowser(App.UpdatePage);
            //    }
            //    else
            //    {
            //        if (MessageBox.Show(this,
            //            App.ComponentUpdateText,
            //            "Install/Update",
            //            MessageBoxButtons.YesNo) == DialogResult.Yes)
            //        {
            //            var updateDlg = new Win32ComponentUpdater(this);
            //            var commonUpdateUi = new ComponentUpdaterUI(updateDlg, App.Updates);
            //            updateDlg.Load += (_, _) => commonUpdateUi.StartUpdate();
            //            updateDlg.Finished += (_, _) =>
            //            {
            //                btnHelp.Image = CreateToolbarIcon(new Font(fcRemixIcon.Families[0], 12),
            //                    RemixIcon.GetFontIcon(RemixIcon.HelpIcon), Color.DimGray);
            //                btnHelp.ForeColor = Color.DimGray;
            //                btnHelp.Text = "Help and support";
            //            };
            //            updateDlg.Visible = true;
            //        }
            //    }
            //}
            //else
            //{
            //    Helpers.OpenBrowser(App.HelpPage);
            //}
        }

        const int WM_CHANGECBCHAIN = 0x030D;

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            using var dlg = new AuthenticationPrompt();
            dlg.PromptText = message ?? "Authentication required";
            if (dlg.ShowDialog() == DialogResult.OK)
            {
                return dlg.Credentials;
            }
            return null;
        }

        private void helpAndSupportToolStripMenuItem_Click(object sender, EventArgs e)
        {
            Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-support.html");
        }

        private void reportAProblemToolStripMenuItem_Click(object sender, EventArgs e)
        {
            Helpers.OpenBrowser("https://subhra74.github.io/xdm/redirect-issue.html");
        }

        private void checkForUpdateToolStripMenuItem_Click(object sender, EventArgs e)
        {

            //Helpers.OpenBrowser(App.UpdatePage);
        }

        public void ExportDownloads(IApp app)
        {
            using var fc = new SaveFileDialog();
            fc.FileName = "xdm-download-list.zip";
            fc.DefaultExt = "zip";
            if (fc.ShowDialog(this) == DialogResult.OK)
            {
                Log.Debug("Exporting to: " + fc.FileName);
                app.Export(fc.FileName);
            }
        }

        private void exportToolStripMenuItem_Click(object sender, EventArgs e)
        {
            ExportClicked?.Invoke(sender, e);
        }

        public void ImportDownloads(IApp app)
        {
            using var fc = new OpenFileDialog();
            fc.DefaultExt = "zip";
            if (fc.ShowDialog(this) == DialogResult.OK)
            {
                Log.Debug("Exporting to: " + fc.FileName);
                app.Import(fc.FileName);
            }
        }

        private void importToolStripMenuItem_Click(object sender, EventArgs e)
        {
            ImportClicked?.Invoke(sender, e);
        }

        public void UpdateBrowserMonitorButton()
        {
            btnMonitoring.Text =
                Config.Instance.IsBrowserMonitoringEnabled ?
                RemixIcon.GetFontIcon("f205") :
                RemixIcon.GetFontIcon("f204");
        }

        private void btnMonitoring_Click(object sender, EventArgs e)
        {
            BrowserMonitoringButtonClicked?.Invoke(sender, e);
        }

        //private void StopSelectedDownloads()
        //{
        //    App?.StopDownloads(this.GetInProgressSelectedItems().Select(x => x.DownloadEntry.Id), true);
        //}

        //        public int LogicalToDeviceUnits(int value)
        //        {
        //#if NET472_OR_GREATER
        //            return base.LogicalToDeviceUnits(value);
        //#else
        //            return WinFormsPolyfill.LogicalToDeviceUnits(value);
        //#endif
        //        }

        private const int EM_SETCUEBANNER = 0x1501;

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern Int32 SendMessage(IntPtr hWnd, int msg, int wParam, [MarshalAs(UnmanagedType.LPWStr)] string lParam);

        //public void LoadDownloadsDB()
        //{
        //    this.downloadsDB.FinishedItems.Load();
        //    this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
        //    this.dgCompletedList.Columns["CompletedDateCol"].HeaderCell.SortGlyphDirection = SortOrder.Descending;
        //    this.dgCompletedList.ClearSelection();

        //    this.downloadsDB.InProgressItems.Load();
        //    this.dgActiveList.AutoGenerateColumns = false;
        //    this.dgActiveList.DataSource = this.downloadsDB.InProgressItems.DataSource;
        //    this.dgActiveList.ClearSelection();
        //}

        public void ShowUpdateAvailableNotification()
        {
            btnHelp.Image = CreateToolbarIcon(ri12Font,
                    RemixIcon.GetFontIcon(RemixIcon.NotificationIcon), FormColors.IconColor);
            btnHelp.Text = "Update(s) available";
            btnHelp.ForeColor = FormColors.IconColor;
            btnHelp.Tag = new object();
        }

        public void ClearUpdateInformation()
        {
            RunOnUIThread(() =>
            {
                btnHelp.Image = CreateToolbarIcon(ri12Font,
                    RemixIcon.GetFontIcon(RemixIcon.HelpIcon), FormColors.FooterForeColor);
                btnHelp.ForeColor = FormColors.FooterForeColor;
                btnHelp.Text = "Help and support";
                btnHelp.Tag = null;
            });
        }

        private void LoadTexts()
        {
            dgActiveList.Columns["NameCol"].HeaderText = TextResource.GetText("SORT_NAME");
            dgActiveList.Columns["ActiveDateCol"].HeaderText = TextResource.GetText("SORT_DATE");
            dgActiveList.Columns["SizeCol"].HeaderText = TextResource.GetText("SORT_SIZE");
            dgActiveList.Columns["StatusCol"].HeaderText = TextResource.GetText("SORT_STATUS");

            dgCompletedList.Columns["CompletedNameCol"].HeaderText = TextResource.GetText("SORT_NAME");
            dgCompletedList.Columns["CompletedDateCol"].HeaderText = TextResource.GetText("SORT_DATE");
            dgCompletedList.Columns["CompletedSizeCol"].HeaderText = TextResource.GetText("SORT_SIZE");

            btnNew.Text = TextResource.GetText("DESC_NEW");
            btnDelete.Text = TextResource.GetText("DESC_DEL");
            btnOpenFile.Text = TextResource.GetText("CTX_OPEN_FILE");
            btnOpenFolder.Text = TextResource.GetText("CTX_OPEN_FOLDER");
            btnResume.Text = TextResource.GetText("MENU_RESUME");
            btnPause.Text = TextResource.GetText("MENU_PAUSE");

            this.toolbarTooltip.SetToolTip(this.btnDelete, TextResource.GetText("MENU_DELETE_DWN"));
            this.toolbarTooltip.SetToolTip(this.btnOpenFolder, TextResource.GetText("CTX_OPEN_FOLDER")); //"Open containing folder");
            this.toolbarTooltip.SetToolTip(this.btnOpenFile, TextResource.GetText("CTX_OPEN_FILE")); //"Open downloaded file");
            this.toolbarTooltip.SetToolTip(this.btnResume, TextResource.GetText("MENU_RESUME")); //"Resume selected downloads");
            this.toolbarTooltip.SetToolTip(this.btnPause, TextResource.GetText("MENU_PAUSE")); //"Pause selected downloads");
            this.toolbarTooltip.SetToolTip(this.btnMenu, TextResource.GetText("LBL_MENU")); //"Menu");

            settingsToolStripMenuItem1.Text = TextResource.GetText("TITLE_SETTINGS");
            removeFinishedToolStripMenuItem.Text = TextResource.GetText("MENU_DELETE_COMPLETED");
            browserMonitoringToolStripMenuItem.Text = TextResource.GetText("SETTINGS_MONITORING");
            helpAndSupportToolStripMenuItem.Text = TextResource.GetText("LBL_SUPPORT_PAGE");
            reportAProblemToolStripMenuItem.Text = TextResource.GetText("LBL_REPORT_PROBLEM");
            checkForUpdateToolStripMenuItem.Text = TextResource.GetText("MENU_UPDATE");
            aboutXDMToolStripMenuItem.Text = TextResource.GetText("MENU_ABOUT");
            exitToolStripMenuItem.Text = TextResource.GetText("MENU_EXIT");
            exportToolStripMenuItem1.Text = TextResource.GetText("MENU_EXPORT");
            importToolStripMenuItem.Text = TextResource.GetText("MENU_IMPORT");
            langToolStripMenuItem1.Text = TextResource.GetText("MENU_LANG");

#if NET5_0_OR_GREATER
            textBox1.PlaceholderText = TextResource.GetText("LBL_SEARCH");
#endif

            dgState.Rows[0].Cells[1].Value = TextResource.GetText("ALL_UNFINISHED");
            dgState.Rows[1].Cells[1].Value = TextResource.GetText("ALL_FINISHED");

            newDownloadToolStripMenuItem.Text = TextResource.GetText("LBL_NEW_DOWNLOAD");
            videoDownloadToolStripMenuItem.Text = TextResource.GetText("LBL_VIDEO_DOWNLOAD");
            batchDownloadToolStripMenuItem.Text = TextResource.GetText("MENU_BATCH_DOWNLOAD");

            label3.Text = TextResource.GetText("SETTINGS_MONITORING");
            btnHelp.Text = TextResource.GetText("LBL_SUPPORT_PAGE");
            btnParallel.Text = TextResource.GetText("DESC_Q_TITLE");

            pauseToolStripMenuItem.Text = TextResource.GetText("MENU_PAUSE");
            resumeToolStripMenuItem.Text = TextResource.GetText("MENU_RESUME");
            deleteToolStripMenuItem.Text = TextResource.GetText("DESC_DEL");
            saveAsToolStripMenuItem1.Text = TextResource.GetText("CTX_SAVE_AS");
            refreshLinkToolStripMenuItem.Text = TextResource.GetText("MENU_REFRESH_LINK");
            showProgressToolStripMenuItem.Text = TextResource.GetText("LBL_SHOW_PROGRESS");
            copyURLToolStripMenuItem.Text = TextResource.GetText("CTX_COPY_URL");
            propertiesToolStripMenuItem.Text = TextResource.GetText("MENU_PROPERTIES");
            openToolStripMenuItem.Text = TextResource.GetText("CTX_OPEN_FILE");
            openFolderToolStripMenuItem.Text = TextResource.GetText("CTX_OPEN_FOLDER");
            deleteDownloadsToolStripMenuItem.Text = TextResource.GetText("MENU_DELETE_DWN");
            copyURLToolStripMenuItem1.Text = TextResource.GetText("CTX_COPY_URL");
            copyFileToolStripMenuItem.Text = TextResource.GetText("CTX_COPY_FILE");
            propertiesToolStripMenuItem1.Text = TextResource.GetText("MENU_PROPERTIES");
            restartToolStripMenuItem.Text = TextResource.GetText("MENU_RESTART");
            scheduleToolStripMenuItem.Text = TextResource.GetText("Q_SCHEDULE_TXT");
            downloadAgainToolStripMenuItem1.Text = TextResource.GetText("MENU_RESTART");
            moveToQueueToolStripMenuItem.Text = TextResource.GetText("Q_MOVE_TO");
        }

        public IInProgressDownloadRow? FindInProgressItem(string id)
        {
            return this.downloadsDB.InProgressItems.FindDownload(id);
        }

        public IFinishedDownloadRow? FindFinishedItem(string id)
        {
            foreach (var item in this.downloadsDB.FinishedItems.AllItems)
            {
                if (id == item.Id)
                {
                    return new FinishedDownloadEntryBinder(item);
                }
            }
            return null;
        }

        public void AddToTop(InProgressDownloadEntry entry)
        {
            this.downloadsDB.InProgressItems.Add(entry);
            this.dgActiveList.SuspendLayout();
            this.dgActiveList.RowCount = 0;
            this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
            this.dgActiveList.Refresh();
            this.dgActiveList.ClearSelection();
            this.dgActiveList.ResumeLayout();
        }

        public void AddToTop(FinishedDownloadEntry entry)
        {
            this.downloadsDB.FinishedItems.Add(entry);
            this.dgCompletedList.SuspendLayout();
            this.dgCompletedList.RowCount = 0;
            this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
            this.dgCompletedList.Refresh();
            this.dgCompletedList.ClearSelection();
            this.dgCompletedList.ResumeLayout();
        }

        public void SwitchToInProgressView()
        {
            dgState.Rows[0].Selected = true;
        }

        public void ClearInProgressViewSelection()
        {
            dgActiveList.ClearSelection();
        }

        public void SwitchToFinishedView()
        {
            dgState.Rows[1].Selected = true;
        }

        public void ClearFinishedViewSelection()
        {
            dgCompletedList.ClearSelection();
        }

        public void RunOnUIThread(Action<string, int, double, long> action, string id, int progress, double speed, long eta)
        {
            if (InvokeRequired)
            {
                BeginInvoke(action, id, progress, speed, eta);
            }
            else
            {
                action.Invoke(id, progress, speed, eta);
            }
        }

        public void Delete(IInProgressDownloadRow row)
        {
            this.downloadsDB.InProgressItems.Delete(row.DownloadEntry);
            this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
            this.dgActiveList.Refresh();
        }

        public void Delete(IFinishedDownloadRow row)
        {
            //var firstVisibleRowIndex = this.dgCompletedList.FirstDisplayedScrollingRowIndex;
            this.downloadsDB.FinishedItems.Delete(row.DownloadEntry);
            this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
            this.dgCompletedList.Refresh();
        }

        public void Delete(IEnumerable<IInProgressDownloadRow> rows)
        {
            this.downloadsDB.InProgressItems.Delete(rows.Select(x => (InProgressDownloadEntryBinder)x));
            this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
            this.dgActiveList.Refresh();
        }

        public void Delete(IEnumerable<IFinishedDownloadRow> rows)
        {
            //var firstVisibleRowIndex = this.dgCompletedList.FirstDisplayedScrollingRowIndex;
            this.downloadsDB.FinishedItems.Delete(rows);
            this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
            this.dgCompletedList.Refresh();
        }

        public IEnumerable<FinishedDownloadEntry> FinishedDownloads
        {
            get => this.downloadsDB.FinishedItems.AllItems;
            set
            {
                this.downloadsDB.FinishedItems.Load(value);
                this.dgCompletedList.RowCount = this.downloadsDB.FinishedItems.RowCount;
                this.dgCompletedList.Columns["CompletedDateCol"].HeaderCell.SortGlyphDirection = SortOrder.Descending;
                this.dgCompletedList.ClearSelection();
            }
        }

        public IEnumerable<InProgressDownloadEntry> InProgressDownloads
        {
            get => this.downloadsDB.InProgressItems.AllItems;
            set
            {
                this.downloadsDB.InProgressItems.Load(value);
                this.dgActiveList.RowCount = this.downloadsDB.InProgressItems.RowCount;
                this.dgActiveList.Columns["ActiveDateCol"].HeaderCell.SortGlyphDirection = SortOrder.Descending;
                //this.dgActiveList.AutoGenerateColumns = false;
                //this.dgActiveList.DataSource = this.downloadsDB.InProgressItems.DataSource;
                this.dgActiveList.ClearSelection();
            }
        }

        public IList<IInProgressDownloadRow> SelectedInProgressRows
        {
            get
            {
                if (dgActiveList.SelectedRows.Count < 1) return new List<IInProgressDownloadRow>(0);
                var list = new List<IInProgressDownloadRow>(dgActiveList.SelectedRows.Count);
                for (var i = 0; i < dgActiveList.SelectedRows.Count; i++)
                {
                    list.Add(this.downloadsDB.InProgressItems[dgActiveList.SelectedRows[i].Index]);
                }
                return list;
            }
        }

        public IList<IFinishedDownloadRow> SelectedFinishedRows
        {
            get
            {
                if (dgCompletedList.SelectedRows.Count < 1) return new List<IFinishedDownloadRow>(0);
                var list = new List<IFinishedDownloadRow>(dgCompletedList.SelectedRows.Count);
                for (var i = 0; i < dgCompletedList.SelectedRows.Count; i++)
                {
                    list.Add(new FinishedDownloadEntryBinder(
                        this.downloadsDB.FinishedItems[dgCompletedList.SelectedRows[i].Index]));
                }
                return list;
            }
        }

        public IButton NewButton { get => this.newButton; }
        public IButton DeleteButton { get => this.deleteButton; }
        public IButton PauseButton { get => this.pauseButton; }

        private void newDownloadToolStripMenuItem_Click(object sender, EventArgs e)
        {
            this.NewDownloadClicked?.Invoke(sender, e);
        }

        public IButton ResumeButton { get => this.resumeButton; }
        public IButton OpenFileButton { get => this.openFileButton; }
        public IButton OpenFolderButton { get => this.openFolderButton; }

        private void importExportToolStripMenuItem_Click(object sender, EventArgs e)
        {
            ImportClicked?.Invoke(sender, e);
        }

        private void toolStripMenuItem1_Click(object sender, EventArgs e)
        {
            ExportClicked?.Invoke(sender, e);
        }

        private void langToolStripMenuItem1_Click(object sender, EventArgs e)
        {
            using (var langDlg = new LanguageSelectionDlg())
            {
                langDlg.ShowDialog(this);
            }
        }

        public bool IsInProgressViewSelected => dgState.SelectedRows.Count == 1 && dgState.Rows[0].Selected;

        public bool Confirm(object? window, string text)
        {
            if (window is not IWin32Window owner)
            {
                owner = this;
            }
            return MessageBox.Show(owner, text, "XDM", MessageBoxButtons.YesNo) == DialogResult.Yes;
        }

        public void ConfirmDelete(string text, out bool approved, out bool deleteFiles)
        {
            var deleteDialog = new DeleteConfirmDlg
            {
                DescriptionText = text
            };
            approved = false;
            deleteFiles = false;
            if (deleteDialog.ShowDialog(this) == DialogResult.OK)
            {
                approved = true;
                deleteFiles = deleteDialog.ShouldDeleteFile;
            }
        }

        public void OpenNewDownloadMenu()
        {
            ctxDownloadMenu.Show(this.btnNew, new Point(0, btnNew.Height));
        }

        public void ShowMessageBox(object? window, string message)
        {
            if (window is not IWin32Window owner)
            {
                owner = this;
            }
            MessageBox.Show(owner, message, "XDM");
        }

        private void CreateMenuItems()
        {
            menuItems = new IMenuItem[]
            {
                new MenuItemWrapper("pause",pauseToolStripMenuItem),
                new MenuItemWrapper("resume",resumeToolStripMenuItem),
                new MenuItemWrapper("delete",deleteToolStripMenuItem),
                new MenuItemWrapper("saveAs",saveAsToolStripMenuItem1),
                new MenuItemWrapper("refresh",refreshLinkToolStripMenuItem),
                new MenuItemWrapper("showProgress",showProgressToolStripMenuItem),
                new MenuItemWrapper("copyURL",copyURLToolStripMenuItem),
                new MenuItemWrapper("properties",propertiesToolStripMenuItem),
                new MenuItemWrapper("open",openToolStripMenuItem),
                new MenuItemWrapper("openFolder",openFolderToolStripMenuItem),
                new MenuItemWrapper("deleteDownloads",deleteDownloadsToolStripMenuItem),
                new MenuItemWrapper("copyURL1",copyURLToolStripMenuItem1),
                new MenuItemWrapper("copyFile",copyFileToolStripMenuItem),
                new MenuItemWrapper("properties1",propertiesToolStripMenuItem1),
                new MenuItemWrapper("restart",restartToolStripMenuItem),
                new MenuItemWrapper("schedule",scheduleToolStripMenuItem),
                new MenuItemWrapper("downloadAgain",downloadAgainToolStripMenuItem1),
                new MenuItemWrapper("moveToQueue",moveToQueueToolStripMenuItem)
            };

            var dict = new Dictionary<string, IMenuItem>();
            foreach (var mi in menuItems)
            {
                dict[mi.Name] = mi;
            }

            this.MenuItemMap = dict;
        }

        public string? SaveFileDialog(string? initialPath)
        {
            using var fc = new SaveFileDialog();
            if (!string.IsNullOrEmpty(initialPath))
            {
                fc.FileName = initialPath;
            }
            //Path.Combine(item.TargetDir ?? Helpers.GetDownloadFolderByFileName(item.Name), item.Name);
            if (fc.ShowDialog(this) != DialogResult.OK)
            {
                return null;
            }
            return fc.FileName;
        }

        public IQueuesWindow CreateQueuesAndSchedulerWindow(IAppUI appUi)
        {
            var queueWindow = new QueuesWindow(appUi);
            return queueWindow;
        }

        public IQueueSelectionDialog CreateQueueSelectionDialog()
        {
            return new QueueSelectionDialog();
        }

        private void EnableDarkMode()
        {
            if (!this.IsHandleCreated)
            {
                this.CreateHandle();
            }
            try
            {
                DarkModeHelper.UseImmersiveDarkMode(this.Handle, true);
                //DarkModeHelper.AllowDarkModeForWindow(this.dgCompletedList.Handle, 1);
                //DarkModeHelper.SetWindowTheme(this.dgCompletedList.Handle, "DarkMode_Explorer", null);
                DarkModeHelper.EnableDarkMode(dgCompletedList);
                DarkModeHelper.EnableDarkMode(dgActiveList);
                DarkModeHelper.EnableDarkMode(dgCategories);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        //public void SetInProgressDownloads(List<InProgressDownloadDTO> list)
        //{
        //    this.inprogressList = list;
        //    this.dgActiveList.RowCount = list.Count;
        //    //this.dgCompletedList.Columns["CompletedDateCol"].HeaderCell.SortGlyphDirection = SortOrder.Descending;
        //    this.dgActiveList.ClearSelection();
        //}

        [DllImport("user32.dll")]
        static extern bool SetForegroundWindow(IntPtr hWnd);
    }
}
