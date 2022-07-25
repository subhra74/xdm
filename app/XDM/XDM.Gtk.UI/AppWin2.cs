//using Gtk;
//using System;
//using System.Collections.Generic;
//using System.IO;
//using Gdk;
//using GLib;
//using XDM.Core.Util;
//using XDMApp;
//using Application = Gtk.Application;
//using DateTime = System.DateTime;
//using Menu = Gtk.Menu;
//using MenuItem = Gtk.MenuItem;
//using Range = System.Range;
//using Window = Gtk.Window;
//using IoPath = System.IO.Path;
//using XDM.Core;
//using XDM.Core.Segmented;
//using System.Threading.Tasks;
//using XDM.Core.Segmented;
//using System.Runtime.InteropServices;
//using System.Linq;

//namespace XDM.GtkUI
//{
//    class AppWin2 : Window, IListUI
//    {
//        private ListStore store;
//        private TreeModelFilter filter;
//        string path = AppDomain.CurrentDomain.BaseDirectory;
//        private IApp app;
//        private const int FILE_NAME = 0, DATE_MODIFIED = 1,
//            PROGRESS = 2, SIZE = 3, FILE_ICON_ID = 4, STATUS = 5,
//            DOWNLOAD_ID = 6, DATE_IN_MILLIS = 7,
//            SIZE_REAL = 8, DOWNLOAD_TYPE = 9;
//        private const int FINISHED_ICON = 0, FINISHED_FILENAME = 1,
//            FINISHED_DATE = 2, FINISHED_SIZE = 3, FINISHED_TYPE = 4,
//            FINISHED_EPOCH = 5, FINISHED_SIZE_LONG = 6,
//            FINISHED_ID = 7, FINISHED_FILE_FULL = 8;
//        private TreeView inProgressTreeView;
//        private TreeView completedTreeView;
//        private Clipboard clipboard;
//        private ListBox lbDownload, lbGalary, lbQueue;
//        private ScrolledWindow swMainScrollView;
//        private Dictionary<string, Pixbuf> fileIconsSmall;
//        private Pixbuf pixOtherSmall;
//        private bool isShowingUnfinishedOnly = true;
//        private StatusIcon StatusIcon;

//        public AppWin2(IApp app) : base("Xtreme Download Manager")
//        {
//            this.app = app;
//            this.app.SetUI(this);
//            Console.WriteLine(path);
//            SetDefaultSize(800, 600);
//            SetPosition(WindowPosition.Center);
//            DeleteEvent += AppWin1_DeleteEvent;
//            StatusIcon = new StatusIcon(
//            new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "video-download-line.svg"), 16, 16, true));

//            LoadFileIcons();
//            CreateTreeView();
//            CreateCompletedTreeView();

//            //this.Titlebar = new HeaderBar() { ShowCloseButton=true,Title="Xtreme Download Manager" };


//            //Gtk.CssProvider provider = new CssProvider();
//            //provider.LoadFromData(@"entry{min-height: 0px; height: 20px;}");
//            //Gtk.StyleContext.AddProviderForScreen(Gdk.Screen.Default, provider, 800);

//            //Pango.FontDescription fontdesc = new Pango.FontDescription();
//            //fontdesc.Size = System.Convert.ToInt32(12 * Pango.Scale.PangoScale);
//            //this.ModifyFont(fontdesc);

//            //Console.WriteLine(Pango.Scale.PangoScale);

//            bool showHeaderBar = true;

//            HeaderBar header = new HeaderBar();
//            header.ShowCloseButton = showHeaderBar;





//            //var menuModel = new GLib.Menu();
//            //menuModel.Append("Add download link", "app.new");
//            //menuModel.Append("Add video download", "app.vid");

//            //Variant v=new Variant(true);

//            //var action = new GLib.SimpleAction("new",new VariantType("int"));
//            //action.AddSignalHandler("connect", null);
//            //action.Activated += (a, b) => { };



//            //Button btn1234 = new Button { Image = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "delete-bin-line.svg"), 16, 16, true)) };
//            //header.PackStart(btn1234);

//            //HBox hb23=new HBox(true,0);
//            //hb23.StyleContext.AddClass("linked");

//            HBox toolbar = null;



//            if (showHeaderBar)
//            {
//                header.Title = "XDM 2021";
//                this.Titlebar = header;

//                Button btn = new Button { Label = "New Download" };
//                btn.Clicked += (a, b) => { ShowNewDownload1Dialog(); };
//                //btn.StyleContext.AddClass("suggested-action");

//                header.PackStart(btn);

//                Button btn123 = new Button { Image = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "video-download-line.svg"), 16, 16, true)) };
//                header.PackStart(btn123);

//                HBox hb231 = new HBox(true, 0);
//                hb231.StyleContext.AddClass("linked");

//                foreach (var text in new string[] { "play-line.svg", "pause-line.svg" })
//                {
//                    Button btn1 = new Button { Image = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", text), 16, 16, true)) };
//                    hb231.Add(btn1);
//                }

//                header.PackStart(hb231);
//                foreach (var text in new string[] { "menu-line.svg"/*, "delete-bin-line.svg", "search-line.svg"*/ })
//                {
//                    Button btn1 = new Button
//                    { Image = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", text), 16, 16, true)) };
//                    header.PackEnd(btn1);
//                }

//                HBox hb2315 = new HBox(true, 0);
//                hb2315.StyleContext.AddClass("linked");
//                foreach (var text in new string[] { "delete-bin-line.svg", "search-line.svg" })
//                {
//                    Button btn1 = new Button
//                    { Image = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", text), 16, 16, true)) };
//                    hb2315.Add(btn1);
//                }
//                header.PackEnd(hb2315);
//            }
//            else
//            {

//                var toolbar1 = new Toolbar();

//                var actionMap = new Dictionary<string, EventHandler>
//                {
//                    ["file-add-line.svg"] = (a, b) =>
//                     {
//                         ShowNewDownload1Dialog();
//                     },
//                    ["video-download-line.svg"] = (a, b) =>
//                    {

//                    },
//                    ["play-line.svg"] = (a, b) =>
//                    {
//                        var idList = new Dictionary<string, string>();
//                        foreach (var row in inProgressTreeView.Selection.GetSelectedRows())
//                        {
//                            filter.GetIter(out TreeIter iter, row);
//                            Value v1 = new Value();
//                            filter.GetValue(iter, DOWNLOAD_ID, ref v1);
//                            var id = (string)v1.Val;
//                            Value v2 = new Value();
//                            filter.GetValue(iter, DOWNLOAD_TYPE, ref v2);
//                            var type = (string)v2.Val;
//                            idList[id] = type;
//                        }
//                        //app.ResumeDownload(idList);
//                    },
//                    ["pause-line.svg"] = (a, b) =>
//                    {
//                        var idList = new List<string>();
//                        foreach (var row in inProgressTreeView.Selection.GetSelectedRows())
//                        {
//                            filter.GetIter(out TreeIter iter, row);
//                            Value v = new Value();
//                            filter.GetValue(iter, DOWNLOAD_ID, ref v);
//                            idList.Add((string)v.Val);
//                        }
//                        app.StopDownloads(idList);
//                    },
//                    ["delete-bin-line.svg"] = (a, b) =>
//                    {
//                        var idList = new List<string>();
//                        foreach (var row in inProgressTreeView.Selection.GetSelectedRows())
//                        {
//                            filter.GetIter(out TreeIter iter, row);
//                            Value v = new Value();
//                            filter.GetValue(iter, DOWNLOAD_ID, ref v);
//                            idList.Add((string)v.Val);
//                        }
//                        //app.DeleteDownloads(idList);
//                    }
//                };

//                foreach (var ent in actionMap)
//                {
//                    var img = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", ent.Key), 20, 20, true));
//                    var btn = new ToolButton(img, "");
//                    btn.Clicked += ent.Value;
//                    toolbar1.Add(btn);
//                    img.Show();
//                    btn.Show();
//                }

//                toolbar1.Show();

//                var toolbar2 = new Toolbar();
//                foreach (var text in new string[] { "search-line.svg", "menu-line.svg" })
//                {
//                    ToolButton btn1 = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", text), 20, 20, true)), "");
//                    toolbar2.Add(btn1);
//                }
//                toolbar2.ShowAll();

//                toolbar = new HBox() { };
//                toolbar.PackStart(toolbar1, false, false, 0);
//                toolbar.PackEnd(toolbar2, false, false, 0);
//                if (Environment.OSVersion.Platform == PlatformID.Win32NT)
//                {
//                    if (Gtk.Settings.Default.ApplicationPreferDarkTheme)
//                    {
//                        toolbar.StyleContext.AddClass("toolbar-border-dark");
//                        toolbar.StyleContext.AddClass("dark2");
//                        toolbar1.StyleContext.AddClass("dark2");
//                        toolbar2.StyleContext.AddClass("dark2");
//                    }
//                    else
//                    {
//                        toolbar.StyleContext.AddClass("toolbar-border-light");
//                    }
//                }
//                toolbar.Show();
//            }




//            //Button btn12 = new Button { Image = new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "pause-white-48dp.svg"), 16, 16, true)) };
//            //header.PackEnd(btn12);

//            //Button btn12 = new Button { Label = "Delete" };
//            //header.PackEnd(btn12);

//            //header.PackEnd(hb23);

//            //MenuBar mb = null;

//            //if (!showHeaderBar)
//            //{
//            //    mb = new MenuBar();

//            //    Menu filemenu = new Menu();
//            //    MenuItem file = new MenuItem("File");
//            //    file.Submenu = filemenu;

//            //    MenuItem exit = new MenuItem("Exit");
//            //    filemenu.Append(exit);

//            //    MenuItem file1 = new MenuItem("Downloads");

//            //    mb.Append(file);
//            //    mb.Append(file1);
//            //    mb.Append(new MenuItem("Tools"));
//            //    mb.Append(new MenuItem("Help"));


//            //    mb.ShowAll();
//            //}



//            //Toolbar toolbar = new Toolbar();
//            //toolbar.ToolbarStyle = ToolbarStyle.Both;

//            //MenuToolButton newtb = new MenuToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "note_add-white-48dp - Copy.svg"), 20, 20, true)), "Add");

//            //ToolButton opentb = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "delete_outline-white-48dp.svg"), 20, 20, true)), "Delete");

//            //ToolButton resumetb = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "play_arrow-white-48dp.svg"), 20, 20, true)), "Resume");
//            //ToolButton savetb = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "pause-white-48dp.svg"), 20, 20, true)), "Pause");
//            //ToolButton savetb2 = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "videocam-white-48dp.svg"), 20, 20, true)), "Media download");
//            //ToolButton savetb3 = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "theaters-white-48dp.svg"), 20, 20, true)), "Pause");
//            //ToolButton savetb4 = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "settings-white-48dp.svg"), 20, 20, true)), "Settings");



//            ////SeparatorToolItem sep = new SeparatorToolItem();
//            //ToolButton quittb = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "more_vert-white-48dp.svg"), 20, 20, true)), "Menu");
//            //ToolButton quittb2 = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "search-white-48dp.svg"), 20, 20, true)), "Search");


//            ////ToolButton newtb = new ToolButton(new Image(Gdk.Pixbuf.NewFromFileAtScaleUtf8(@"D:\Downloads\add_circle_outline-white-48dp.svg", 24,24, true)),"Add");
//            ////ToolButton opentb = new ToolButton(new Image(Gdk.Pixbuf.NewFromFileAtScaleUtf8(@"D:\Downloads\remove_circle_outline-white-48dp.svg", 32, 32, true)), "Delete");

//            ////ToolButton resumetb = new ToolButton(new Image(Gdk.Pixbuf.NewFromFileAtScaleUtf8(@"D:\Downloads\play_circle_outline-white-48dp.svg", 32, 32, true)), "Resume");
//            ////ToolButton savetb = new ToolButton(new Image(Gdk.Pixbuf.NewFromFileAtScaleUtf8(@"D:\Downloads\pause_circle_outline-white-48dp.svg", 32, 32, true)), "Pause");
//            ////SeparatorToolItem sep = new SeparatorToolItem();
//            ////ToolButton quittb = new ToolButton(new Image(Gdk.Pixbuf.NewFromFileAtScaleUtf8(@"D:\Downloads\more_vert-white-48dp.svg", 32, 32, true)),"Menu");
//            ////ToolButton quittb2 = new ToolButton(new Image(Gdk.Pixbuf.NewFromFileAtScaleUtf8(@"D:\Downloads\tune-white-48dp.svg", 32, 32, true)), "Menu");

//            ////quittb.Toggled += Quittb_Toggled;

//            //newtb.Clicked += Newtb_Clicked;

//            //toolbar.Insert(newtb, 0);
//            //toolbar.Insert(opentb, 1);
//            //toolbar.Insert(new SeparatorToolItem(), 2);
//            //toolbar.Insert(savetb, 3);
//            //toolbar.Insert(resumetb, 4);
//            //toolbar.Insert(new SeparatorToolItem(), 5);
//            ////toolbar.Insert(savetb2, 6);
//            ////toolbar.Insert(savetb3, 7);
//            //toolbar.Insert(savetb4, 8);
//            ////toolbar.Insert(sep, 4);


//            //Toolbar toolbar2 = new Toolbar();
//            //toolbar2.ToolbarStyle = ToolbarStyle.Icons;
//            //toolbar.Insert(quittb2, 9);
//            //toolbar2.Insert(quittb, 2);



//            //HBox hBox = new HBox();

//            //hBox.PackStart(toolbar, false, false, 0);
//            ////hBox.PackEnd(toolbar2, false, false, 0);



//            //vbox.PackStart(mb, false, false, 0);
//            //vbox.PackStart(hBox, false, false, 0);





//            //HBox hBox = new HBox();



//            Paned paned = new Paned(Orientation.Horizontal);
//            paned.Position = 200;

//            VBox vb12 = new VBox();
//            vb12.StyleContext.AddClass("dark");
//            //var hbDownloadLabel = new HBox { Margin = 10, MarginStart = 5, Visible = true};
//            //hbDownloadLabel.PackStart(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "folder-download-line.svg"), 16, 16, true)){Visible = true}, false, false, 10);
//            //hbDownloadLabel.PackStart(new Label { Text = "Downloads", Halign = Align.Start, Visible = true}, false, false, 0);

//            //vb12.PackStart(hbDownloadLabel, false, true, 0);

//            if (!showHeaderBar)
//            {
//                //var header2 = new HeaderBar();
//                //header2.Title = "X T R E M E";
//                //header2.Subtitle = "DOWNLOAD MANAGER";
//                //header2.HasSubtitle = true;
//                //header2.Visible = true;
//                //vb12.PackStart(header2, false, true, 0);
//            }




//            //this.lbDownload = CreateListBox(new Dictionary<string, string>()
//            //{
//            //    //["All Incomplete"] = "download-line.svg",
//            //    ["Incomplete"] = "download-line.svg",/*"time-line.svg",*/
//            //    //["Paused/Stopped"] = "pause-line.svg"/*"task-line.svg"*/,
//            //    ["Completed"] = "check-line.svg"
//            //}, 5);

//            //this.lbDownload.RowSelected += (a, b) =>
//            //{
//            //    if (b.Row.Index == 0)
//            //    {
//            //        isShowingUnfinishedOnly = true;
//            //        RefereshListView();
//            //        swMainScrollView.Remove(this.completedTreeView);
//            //        swMainScrollView.Add(this.inProgressTreeView);
//            //    }
//            //    else
//            //    {
//            //        isShowingUnfinishedOnly = false;
//            //        RefereshListView();
//            //        swMainScrollView.Remove(this.inProgressTreeView);
//            //        swMainScrollView.Add(this.completedTreeView);
//            //    }
//            //};

//            //this.lbGalary = CreateListBox(
//            //    new Dictionary<string, string>()
//            //    {
//            //        //["All Files"] = "file-line.svg",
//            //        ["Documents"] = "file-text-line.svg"/*"task-line.svg"*/,
//            //        ["Music"] = "music-2-line.svg",
//            //        ["Video"] = "film-line.svg",
//            //        ["Compressed"] = "file-zip-line.svg",
//            //        ["Images"] = "image-line.svg",
//            //        ["Applications"] = "apps-line.svg",
//            //        ["Other"] = "file-4-line.svg"
//            //    }, 20);

//            //this.lbQueue = CreateQueueListBox();



//            //vb12.PackStart(new Label { Text = "Downloads", Margin = 10, MarginStart = 15, Halign = Align.Start, Visible = true }, false, true, 0);
//            vb12.PackStart(this.CreateTree1(), true, true, 0);
//            //vb12.PackStart(new Label { Text = "Completed Downloads", Margin = 10, MarginStart = 15, Halign = Align.Start, Visible = true }, false, true, 0);
//            //vb12.PackStart(this.lbGalary, false, true, 0); //vb12.PackStart(CreateCategoryListBox(), false, true, 0);
//            //vb12.PackStart(new Label { Text = "Queues", Margin = 10, MarginStart = 15, Halign = Align.Start, Visible = true }, false, true, 0);
//            //vb12.PackStart(this.lbQueue, true, true, 0);

//            var sw2 = new ScrolledWindow { OverlayScrolling = true };
//            sw2.SetPolicy(PolicyType.Automatic, PolicyType.Automatic);

//            sw2.Add(vb12);

//            //sw2.Add(CreateTreeView2());

//            paned.Add1(sw2);

//            swMainScrollView = new ScrolledWindow
//            {
//                OverlayScrolling = true
//            };
//            swMainScrollView.SetPolicy(PolicyType.Automatic, PolicyType.Automatic);
//            swMainScrollView.Add(inProgressTreeView);
//            swMainScrollView.Show();

//            VBox vb32 = new VBox();
//            if (!showHeaderBar)
//                vb32.PackStart(toolbar, false, false, 0);
//            vb32.PackStart(swMainScrollView, true, true, 0);
//            vb32.Show();

//            paned.Add2(vb32);

//            //hBox.PackStart(sw2, true, true, 0);
//            //hBox.PackStart(sw, true, true, 0);
//            VBox vbox = new VBox(false, 2);

//            vbox.PackStart(paned, true, true, 0);

//            Add(vbox);

//            if (showHeaderBar)
//                header.ShowAll();
//            vb12.Show();
//            sw2.Show();
//            paned.Show();
//            vbox.Show();

//            for (int i = 0; i < 10000; i++)
//            {
//                store.AppendValues("不能下载为自动选择清晰度模式下的视频", "sample date", 30, "aaaaa");
//            }
//            clipboard = Clipboard.Get(Gdk.Selection.Clipboard);


//            var items = new Dictionary<string, RowItem>();
//            foreach (var ent in Enumerable.Range(1,100))
//            {
//                items[Guid.NewGuid().ToString()] = new RowRef
//                {
//                    TreeIter = store.AppendValues(/*"不能下载为自动选择清晰度模式下的视频"*/
//                   @"বেস্বাধীনভাবে সমান মর্যাদা" /*ent.Name*/, "10/10/20", 20, "123 gb", "",
//                     "Finished" ,
//                    "121312323", 0, 0, "")
//                    // TreeIter = store.AppendValues(/*"不能下载为自动选择清晰度模式下的视频"*/
//                    //"hello"/*@"স্বাধীনভাবে সমান মর্যাদা"*/ /*ent.Name*/, ent.DateAdded.ToShortDateString(), ent.Progress, Helpers.FormatSize(ent.Size), "",
//                    // ent.Status == DownloadStatus.Finished ? "Finished" : "Stopped",
//                    // ent.Id, ent.DateAdded.Ticks, ent.Size, ent.DownloadType)
//                };
//            }


//            //app.LoadDownloadList();

//            //Realized += (a, b) =>
//            //{
//            //    var hwnd= FindWindowEx()
//            //};

//            //Shown += (a, b) =>
//            // {
//            //     var hwnd = FindWindowEx(IntPtr.Zero, IntPtr.Zero, null, "XDM 2021");
//            //     if (hwnd != IntPtr.Zero)
//            //     {
//            //         var wl = GetWindowLong(hwnd, -16);
//            //         SetWindowLong(hwnd, -16, wl | 0x00020000);
//            //     }
//            // };
//        }


//        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
//        static extern IntPtr FindWindowEx(IntPtr parentHandle, IntPtr childAfter, string lclassName, string windowTitle);

//        [DllImport("user32.dll")]
//        static extern int SetWindowLong(IntPtr hWnd, int nIndex, uint dwNewLong);

//        [DllImport("user32.dll", EntryPoint = "GetWindowLong")]
//        static extern uint GetWindowLong(IntPtr hWnd, int nIndex);

//        private void CreateTreeView()
//        {



//            store = new ListStore(typeof(string),   // file name
//                typeof(string),                                 // date modified
//                typeof(int),                                    // progress
//                typeof(string),                                 // size
//                typeof(string),                                 // file icon id
//                typeof(string),                                 // status
//                typeof(string),                                 // id
//                typeof(long),                                   // date in epoch
//                typeof(long),                                   // size in long
//                typeof(string)                                 // download type
//                );

//            //store.SetSortFunc(DATE_MODIFIED, (model, iter1, iter2) =>
//            //{
//            //    var t1 = (long)store.GetValue(iter1, DATE_IN_MILLIS);
//            //    var t2 = (long)store.GetValue(iter2, DATE_IN_MILLIS);
//            //    if (t1 > t2) return 1;
//            //    if (t2 > t1) return -1;
//            //    return 0;
//            //});

//            //store.SetSortFunc(SIZE, (model, iter1, iter2) =>
//            //{
//            //    var s1 = (long)store.GetValue(iter1, SIZE_REAL);
//            //    var s2 = (long)store.GetValue(iter2, SIZE_REAL);
//            //    if (s1 > s2) return 1;
//            //    if (s2 > s1) return -1;
//            //    return 0;
//            //});

//            this.filter = new TreeModelFilter(store, null);
//            filter.VisibleFunc = (model, iter) =>
//            {
//                var status = (string)model.GetValue(iter, STATUS);//"Finished"
//                return this.isShowingUnfinishedOnly ? "Finished" != status : "Finished" == status;
//            };

//            inProgressTreeView = new TreeView(filter);
//            inProgressTreeView.Selection.Mode = SelectionMode.Multiple;

//            //File name column
//            var fileNameColumn = new TreeViewColumn
//            {
//                Resizable = true,
//                Reorderable = true,
//                Title = "Name",
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 200
//            };

//            fileNameColumn.SortColumnId = FILE_NAME;

//            var fileIconRenderer = new CellRendererPixbuf { };

//            fileIconRenderer.SetPadding(5, 8);

//            fileNameColumn.PackStart(fileIconRenderer, false);

//            fileNameColumn.SetCellDataFunc(fileIconRenderer, new CellLayoutDataFunc(GetFileIcon));
//            //TODO: get icon from model

//            var fileNameRendererText = new CellRendererText();
//            fileNameRendererText.SetPadding(5, 8);
//            fileNameColumn.PackStart(fileNameRendererText, false);
//            fileNameColumn.SetAttributes(fileNameRendererText, "text", FILE_NAME);
//            inProgressTreeView.AppendColumn(fileNameColumn);

//            //File size column
//            var fileSizeRendererText = new CellRendererText();
//            fileSizeRendererText.Xalign = 1.0f;
//            fileSizeRendererText.SetPadding(5, 8);
//            var fileSizeColumn = new TreeViewColumn
//            {
//                Resizable = true,
//                Reorderable = true,
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 80,
//                Title = "Size",
//            };
//            fileSizeColumn.PackEnd(fileSizeRendererText, false);
//            fileSizeColumn.SetAttributes(fileSizeRendererText, "text", SIZE);
//            fileSizeColumn.SortColumnId = SIZE_REAL;
//            inProgressTreeView.AppendColumn(fileSizeColumn);

//            //File progress column
//            var fileRendererProgress = new CellRendererProgress()
//            {
//                //Text = "Downloading",
//            };

//            fileRendererProgress.SetPadding(5, 10);
//            var progressColumn = new TreeViewColumn("Progress", fileRendererProgress, "value", PROGRESS)
//            {
//                Resizable = true,
//                Reorderable = true,
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 80
//            };
//            progressColumn.SetAttributes(fileRendererProgress, "value", PROGRESS);
//            inProgressTreeView.AppendColumn(progressColumn);

//            //Last modified column
//            var lastModifiedRendererText = new CellRendererText();
//            lastModifiedRendererText.SetPadding(5, 8);
//            var lastModifiedColumn = new TreeViewColumn("Date added", lastModifiedRendererText, "text", DATE_MODIFIED)
//            {
//                Resizable = true,
//                Reorderable = true,
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 100
//            };
//            lastModifiedColumn.SortColumnId = DATE_IN_MILLIS;
//            lastModifiedColumn.SetAttributes(lastModifiedRendererText, "text", DATE_MODIFIED);
//            inProgressTreeView.AppendColumn(lastModifiedColumn);

//            //Download status column
//            var statusRendererText = new CellRendererText();
//            statusRendererText.SetPadding(5, 8);
//            var statusColumn = new TreeViewColumn("Status", statusRendererText, "text", STATUS)
//            {
//                Resizable = true,
//                Reorderable = true,
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 80
//            };
//            statusColumn.SortColumnId = STATUS;
//            statusColumn.SetAttributes(statusRendererText, "text", STATUS);
//            inProgressTreeView.AppendColumn(statusColumn);

//            inProgressTreeView.StyleContext.AddClass("listt");

//            inProgressTreeView.Show();
//        }

//        private void CreateCompletedTreeView()
//        {
//            completedTreeView = new TreeView(filter);
//            completedTreeView.Selection.Mode = SelectionMode.Multiple;

//            //File name column
//            var fileNameColumn = new TreeViewColumn
//            {
//                Resizable = true,
//                Reorderable = true,
//                Title = "Name",
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 400
//            };

//            fileNameColumn.SortColumnId = FILE_NAME;

//            var fileIconRenderer = new CellRendererPixbuf { };

//            fileIconRenderer.SetPadding(5, 8);
//            fileNameColumn.PackStart(fileIconRenderer, false);

//            fileNameColumn.SetCellDataFunc(fileIconRenderer, new CellLayoutDataFunc(GetFileIcon));
//            //TODO: get icon from model

//            var fileNameRendererText = new CellRendererText();
//            fileNameRendererText.SetPadding(5, 8);
//            fileNameColumn.PackStart(fileNameRendererText, false);
//            fileNameColumn.SetAttributes(fileNameRendererText, "text", FILE_NAME);
//            completedTreeView.AppendColumn(fileNameColumn);

//            //File size column
//            var fileSizeRendererText = new CellRendererText();
//            fileSizeRendererText.Xalign = 1.0f;
//            fileSizeRendererText.SetPadding(5, 8);
//            var fileSizeColumn = new TreeViewColumn
//            {
//                Resizable = true,
//                Reorderable = true,
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 80,
//                Title = "Size",
//            };
//            fileSizeColumn.PackEnd(fileSizeRendererText, false);
//            fileSizeColumn.SetAttributes(fileSizeRendererText, "text", SIZE);
//            fileSizeColumn.SortColumnId = SIZE_REAL;
//            completedTreeView.AppendColumn(fileSizeColumn);


//            //Last modified column
//            var lastModifiedRendererText = new CellRendererText();
//            lastModifiedRendererText.SetPadding(5, 8);
//            var lastModifiedColumn = new TreeViewColumn("Date added", lastModifiedRendererText, "text", DATE_MODIFIED)
//            {
//                Resizable = true,
//                Reorderable = true,
//                Sizing = TreeViewColumnSizing.Fixed,
//                FixedWidth = 100
//            };
//            lastModifiedColumn.SortColumnId = DATE_IN_MILLIS;
//            lastModifiedColumn.SetAttributes(lastModifiedRendererText, "text", DATE_MODIFIED);
//            completedTreeView.AppendColumn(lastModifiedColumn);

//            completedTreeView.StyleContext.AddClass("listt");
//            completedTreeView.Show();

//        }

//        public RowItem AddItem(string file, string date, int progress, string size, string id,
//            long realSize, DateTime dateAdded, string type)
//        {
//            return new RowRef { TreeIter = store.AppendValues(file, date, progress, size, "", "Downloading", id, dateAdded.Ticks, realSize, type) };
//        }

//        public RowItem AddItemToTop(string file, string date, int progress, string size, string id,
//            long realSize, DateTime dateAdded, string type)
//        {
//            var treeIter = store.InsertWithValues(0, file, date, progress, size, "", "Downloading", id, dateAdded.Ticks, realSize, type);
//            inProgressTreeView.SetCursor(store.GetPath(treeIter), inProgressTreeView.GetColumn(0), false);
//            app.SaveState();
//            RefereshListView();
//            return new RowRef { TreeIter = treeIter };
//        }

//        public void UpdateItem(RowItem item, string name, string size, long realSize)
//        {
//            Gtk.Application.Invoke((a, b) =>
//            {
//                store.SetValue(((RowRef)item).TreeIter, FILE_NAME, name);
//                store.SetValue(((RowRef)item).TreeIter, SIZE, size);
//                store.SetValue(((RowRef)item).TreeIter, SIZE_REAL, realSize);
//                app.SaveState();
//                RefereshListView();
//            });
//        }

//        public void UpdateProgress(RowItem item, int progress)
//        {
//            if (progress <= 100 && progress >= 0)
//            {
//                Gtk.Application.Invoke((a, b) =>
//                {
//                    try
//                    {
//                        store.SetValue(((RowRef)item).TreeIter, PROGRESS, progress);
//                        app.SaveState();
//                        RefereshListView();
//                    }
//                    catch (Exception e)
//                    {
//                        Console.WriteLine(e);
//                    }
//                });
//            }
//        }

//        public void DownloadFinished(RowItem item, long size = -1)
//        {
//            Gtk.Application.Invoke((a, b) =>
//            {
//                var iter = ((RowRef)item).TreeIter;
//                store.SetValue(iter, PROGRESS, 100);
//                store.SetValue(iter, STATUS, "Finished");
//                if ((long)store.GetValue(iter, SIZE_REAL) < 0 && size >= 0)
//                {
//                    store.SetValue(iter, SIZE, Helpers.FormatSize(size));
//                    store.SetValue(iter, SIZE_REAL, size);
//                }
//                app.SaveState();
//                RefereshListView();
//            });

//        }

//        public void DownloadFailed(RowItem item)
//        {
//            Gtk.Application.Invoke((a, b) =>
//            {
//                store.SetValue(((RowRef)item).TreeIter, STATUS, "Failed");
//                app.SaveState();
//                RefereshListView();
//            });

//        }

//        public void DownloadCanelled(RowItem item)
//        {
//            Gtk.Application.Invoke((a, b) =>
//            {
//                store.SetValue(((RowRef)item).TreeIter, STATUS, "Stopped");
//                app.SaveState();
//                RefereshListView();
//            });

//        }

//        public void DeleteDownload(List<RowItem> items)
//        {
//            if (items != null)
//            {
//                foreach (var item in items)
//                {
//                    var iter = ((RowRef)item).TreeIter;
//                    store.Remove(ref iter);
//                }
//            }
//            RefereshListView();
//        }

//        private void AppWin1_DeleteEvent(object o, DeleteEventArgs args)
//        {
//            Application.Quit();
//        }

//        private ListBox CreateListBox(Dictionary<string, string> labels, int startMargin = 15)
//        {
//            ListBox lb = new ListBox();
//            lb.StyleContext.AddClass("dark");
//            //lb.OverrideBackgroundColor(StateFlags.Normal, new Label().StyleContext.GetBackgroundColor(StateFlags.Normal));
//            lb.Visible = true;

//            //foreach (var text in new string[] { "All Downloads", "Finished/Completed", "Downloading", "Paused/Stopped" })
//            foreach (var ent in labels)
//            {
//                var r1 = new ListBoxRow();
//                r1.Visible = true;
//                var hBox = new HBox();
//                hBox.Margin = 5;
//                hBox.Visible = true;
//                hBox.MarginStart = startMargin;
//                var lbl = new Label { Text = ent.Key, Halign = Align.Start, Visible = true };
//                hBox.PackStart(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", ent.Value/*"folder-download-line.svg"*/), 16, 16, true)) { Visible = true }, false, false, 10);
//                hBox.PackStart(lbl, true, true, 0);
//                r1.Add(hBox);
//                lb.Add(r1);
//            }

//            //lb.SelectRow(lb.GetRowAtIndex(0));
//            return lb;
//        }

//        private ListBox CreateQueueListBox()
//        {
//            ListBox lb = new ListBox();
//            lb.StyleContext.AddClass("dark");
//            lb.Visible = true;
//            foreach (var text in new string[] { "Default Queue" })
//            {
//                var r1 = new ListBoxRow();
//                r1.Visible = true;
//                var hBox = new HBox();
//                hBox.Visible = true;
//                var lbl = new Label { Text = text, Halign = Align.Start, Visible = true };
//                lbl.Margin = 5;
//                hBox.PackStart(lbl, true, true, 20);
//                r1.Add(hBox);
//                lb.Add(r1);
//            }

//            //lb.SelectRow(lb.GetRowAtIndex(0));
//            return lb;
//        }

//        private Widget CreateTree1()
//        {
//            TreeView tree = new TreeView();
//            tree.HeadersVisible = false;

//            TreeViewColumn languages = new TreeViewColumn();
//            languages.Title = "Programming languages";

//            CellRendererText cell = new CellRendererText();
//            cell.SetPadding(5, 5);
//            languages.PackStart(cell, true);
//            languages.AddAttribute(cell, "text", 0);

//            TreeStore treestore = new TreeStore(typeof(string), typeof(string));

//            TreeIter iter = treestore.AppendValues("Scripting languages");
//            treestore.AppendValues(iter, "Python");
//            treestore.AppendValues(iter, "PHP");
//            treestore.AppendValues(iter, "Perl");
//            treestore.AppendValues(iter, "Ruby");

//            //iter = treestore.AppendValues("Compiling languages");
//            //treestore.AppendValues(iter, "C#");
//            //treestore.AppendValues(iter, "C++");
//            //treestore.AppendValues(iter, "C");
//            //treestore.AppendValues(iter, "Java");

//            tree.AppendColumn(languages);
//            tree.Model = treestore;

//            tree.Selection.Mode = SelectionMode.Browse;

//            ScrolledWindow sw2 = new ScrolledWindow();
//            sw2.OverlayScrolling = true;
//            sw2.SetPolicy(PolicyType.Automatic, PolicyType.Automatic);
//            sw2.Add(tree);
//            sw2.ShowAll();
//            return sw2;
//        }

//        public void ShowNewDownloadDialog(Message message, string providedFileName = null)
//        {
//            var builder = new Builder();
//            builder.AddFromFile(System.IO.Path.Combine(path, "glade", "new-download.glade"));
//            var window = (Window)builder.GetObject("new-download-dialog");
//            var txtUrl = (Entry)builder.GetObject("txt-url");
//            var txtFile = (Entry)builder.GetObject("txt-file");

//            var lblFileSize = (Label)builder.GetObject("lbl-file-size");
//            lblFileSize.Text = "";
//            txtUrl.Text = message.Url;

//            var fileName = providedFileName ?? Helpers.SanitizeFileName(IoPath.GetFileName(new Uri(message.Url).LocalPath));
//            string downloadFolder = Helpers.GetDownloadFolderByFileName(fileName);
//            Console.WriteLine(downloadFolder);

//            txtFile.Text = fileName;

//            var http = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo
//            {
//                Uri = message.Url,
//                Headers = message.RequestHeaders,
//                Cookies = message.Cookies
//            });
//            if (!string.IsNullOrWhiteSpace(providedFileName))
//            {
//                //http.SetFileName(providedFileName, true);
//            }

//            var btnBrowse = (Button)builder.GetObject("btn-browse");
//            var btnCancel = (Button)builder.GetObject("btn-cancel");
//            var btnDownload = (Button)builder.GetObject("btn-download");

//            btnBrowse.Clicked += (a, b) =>
//            {
//                Directory.CreateDirectory(downloadFolder);
//                var fc = new FileChooserNative("Save", window, FileChooserAction.Save, "Save", "Cancel")
//                {
//                    CurrentName = fileName,
//                    DoOverwriteConfirmation = true
//                };
//                var filter = new FileFilter() { Name = "All files" };
//                filter.AddPattern("*");
//                fc.AddFilter(filter);
//                fc.SetCurrentFolder(downloadFolder);
//                if (fc.Run() == (int)Gtk.ResponseType.Accept)
//                {
//                    var file = IoPath.GetFileName(fc.Filename);
//                    var folder = IoPath.GetDirectoryName(fc.Filename);

//                    if (!fileName.Equals(file, StringComparison.InvariantCultureIgnoreCase))
//                    {
//                        txtFile.Text = file;
//                        //http.SetFileName(txtFile.Text, false);
//                    }
//                    http.SetTargetDirectory(folder);
//                }
//                fc.Dispose();
//            };

//            btnDownload.Clicked += (a, b) =>
//            {
//                //app.StartDownload(http, true);
//                //http.Start();
//                //window.Dispose();
//            };

//            btnCancel.Clicked += (a, b) =>
//            {
//                http.Stop();
//                window.Dispose();
//            };

//            //void HandleProbeResult(object source, EventArgs args)
//            //{
//            //    Console.WriteLine("File name: " + http.TargetFileName);
//            //    Application.Invoke((a, b) =>
//            //    {
//            //        txtFile.Text = http.TargetFileName;
//            //        downloadFolder = IoPath.GetDirectoryName(http.TargetFile);
//            //        if (http.FileSize > 0)
//            //        {
//            //            lblFileSize.Text = Helpers.FormatSize(http.FileSize);
//            //        }
//            //        else
//            //        {
//            //            lblFileSize.Text = "---";
//            //        }
//            //    });
//            //}
//            //http.Probed += HandleProbeResult;
//            //window.DestroyEvent += (a, b) =>
//            //{
//            //    http.Probed -= HandleProbeResult;
//            //};

//            Application.Invoke((a, b) =>
//            {
//                window.Show();
//                //http.ProbeTargetAsync();
//            });
//        }

//        void ShowNewDownload1Dialog()
//        {
//            var builder = new Builder();
//            builder.AddFromFile(System.IO.Path.Combine(path, "glade", "url-capture.glade"));
//            var window = (Window)builder.GetObject("url-capture");
//            window.Show();
//            var btnDownload = (Button)builder.GetObject("download-btn");
//            var textField = (Entry)builder.GetObject("url-text");
//            var btnCancel = (Button)builder.GetObject("cancel-btn");

//            btnCancel.Clicked += (a, b) => window.Dispose();

//            var urlText = clipboard.WaitForText();
//            if (urlText != null && Helpers.IsUriValid(urlText))
//            {
//                textField.Text = urlText;
//            }

//            btnDownload.Clicked += (a, b) =>
//            {
//                var text = textField.Text;
//                var validUrl = true;
//                if (!string.IsNullOrWhiteSpace(text))
//                {
//                    try
//                    {
//                        new Uri(text);
//                    }
//                    catch
//                    {
//                        validUrl = false;
//                    }
//                }
//                else
//                {
//                    validUrl = false;
//                }

//                if (!validUrl)
//                {
//                    var msgDlg = new MessageDialog(window,
//                        DialogFlags.UseHeaderBar | DialogFlags.Modal | DialogFlags.DestroyWithParent,
//                        MessageType.Error, ButtonsType.Ok, false, "Address Invalid")
//                    {
//                        SecondaryText = "Please enter valid address for download"
//                    };
//                    msgDlg.Run();
//                    msgDlg.Dispose();
//                    return;
//                }

//                window.Dispose();
//                ShowNewDownloadDialog(new Message { Url = text });
//            };
//        }

//        public List<InProgressDownloadEntry> GetListData()
//        {
//            var list = new List<InProgressDownloadEntry>();
//            store.Foreach((model, treePath, iter) =>
//            {
//                var ent = new InProgressDownloadEntry
//                {
//                    Name = (string)store.GetValue(iter, FILE_NAME),
//                    Size = (long)store.GetValue(iter, SIZE_REAL),
//                    DateAdded = new DateTime((long)store.GetValue(iter, DATE_IN_MILLIS)),
//                    Category = "Other",
//                    Id = (string)store.GetValue(iter, DOWNLOAD_ID),
//                    Progress = (int)store.GetValue(iter, PROGRESS),
//                    Status = ((string)store.GetValue(iter, STATUS)) == "Finished" ? DownloadStatus.Finished : DownloadStatus.Stopped,
//                    DownloadType = (string)store.GetValue(iter, DOWNLOAD_TYPE)
//                };
//                list.Add(ent);
//                return false;
//            });
//            return list;
//        }

//        public Dictionary<string, RowItem> SetListData(List<InProgressDownloadEntry> list)
//        {

//            var items = new Dictionary<string, RowItem>();
//            foreach (var ent in list)
//            {
//                items[ent.Id] = new RowRef
//                {
//                    TreeIter = store.AppendValues(/*"不能下载为自动选择清晰度模式下的视频"*/
//                   @"বেস্বাধীনভাবে সমান মর্যাদা" /*ent.Name*/, "10/10/20", 20, "123 gb", "",
//                    ent.Status == DownloadStatus.Finished ? "Finished" : "Stopped",
//                    "121312323", 0, 0, "")
//                    // TreeIter = store.AppendValues(/*"不能下载为自动选择清晰度模式下的视频"*/
//                    //"hello"/*@"স্বাধীনভাবে সমান মর্যাদা"*/ /*ent.Name*/, ent.DateAdded.ToShortDateString(), ent.Progress, Helpers.FormatSize(ent.Size), "",
//                    // ent.Status == DownloadStatus.Finished ? "Finished" : "Stopped",
//                    // ent.Id, ent.DateAdded.Ticks, ent.Size, ent.DownloadType)
//                };
//            }



//            //var pixDocLarge = new Pixbuf(IoPath.Combine(path, "images", "file-text-line.svg"), 64, 64, true);
//            //var pixMusicLarge = new Pixbuf(IoPath.Combine(path, "images", "music-2-line.svg"), 64, 64, true);
//            //var pixVideoLarge = new Pixbuf(IoPath.Combine(path, "images", "film-line.svg"), 64, 64, true);
//            //var pixZipLarge = new Pixbuf(IoPath.Combine(path, "images", "file-zip-line.svg"), 64, 64, true);
//            //var pixAppsLarge = new Pixbuf(IoPath.Combine(path, "images", "apps-line.svg"), 64, 64, true);
//            //var pixImgLarge = new Pixbuf(IoPath.Combine(path, "images", "image-line.svg"), 64, 64, true);
//            //var pixOtherLarge = new Pixbuf(IoPath.Combine(path, "images", "file-line.svg"), 64, 64, true);

//            //var fileIconsLarge = new Dictionary<string, Pixbuf>
//            //{
//            //    ["MP4"] = pixVideoLarge,
//            //    ["MKV"] = pixVideoLarge,
//            //    ["WEBM"] = pixVideoLarge,
//            //    ["TS"] = pixVideoLarge,
//            //    ["MOV"] = pixVideoLarge,
//            //    ["MP3"] = pixMusicLarge,
//            //    ["M4A"] = pixMusicLarge,
//            //    ["AAC"] = pixMusicLarge,
//            //    ["OGG"] = pixMusicLarge,
//            //    ["ZIP"] = pixZipLarge,
//            //    ["BZ2"] = pixZipLarge,
//            //    ["TBZ"] = pixZipLarge,
//            //    ["XZ"] = pixZipLarge,
//            //    ["TAR"] = pixZipLarge,
//            //    ["7Z"] = pixZipLarge,
//            //    ["EXE"] = pixAppsLarge,
//            //    ["JAR"] = pixAppsLarge,
//            //    ["MSI"] = pixAppsLarge,
//            //    ["JPG"] = pixVideoLarge,
//            //    ["JPEG"] = pixVideoLarge,
//            //    ["GIF"] = pixVideoLarge,
//            //    ["PNG"] = pixVideoLarge,
//            //    ["DOCX"] = pixDocLarge,
//            //    ["DOC"] = pixDocLarge,
//            //    ["PDF"] = pixDocLarge,
//            //    ["ODT"] = pixDocLarge
//            //};



//            return items;
//        }

//        public bool ConfirmDelete(string message)
//        {
//            var messageBox = new MessageDialog(this, DialogFlags.Modal, MessageType.Other, ButtonsType.YesNo, message);
//            messageBox.ContentArea.Margin = 10;
//            var ret = messageBox.Run() == (int)Gtk.ResponseType.Yes;
//            messageBox.Dispose();
//            return ret;
//        }

//        public void ShowVideoDownloadDialog(string id, string name)
//        {
//            var builder = new Builder();
//            builder.AddFromFile(System.IO.Path.Combine(path, "glade", "vid-capture.glade"));
//            var window = (Window)builder.GetObject("vid-win");
//            var imgbox = (Image)builder.GetObject("imgbox");
//            var txtFile = (Entry)builder.GetObject("txt-file");

//            imgbox.Pixbuf = new Gdk.Pixbuf(IoPath.Combine(path, "images", "film-line.svg"), 48, 48, true);

//            txtFile.Text = Helpers.SanitizeFileName(name);

//            var btnBrowse = (Button)builder.GetObject("btn-browse");
//            var btnCancel = (Button)builder.GetObject("btn-cancel");
//            var btnDownload = (Button)builder.GetObject("btn-download");

//            string folder = Helpers.GetDownloadFolderByFileName(name);

//            btnBrowse.Clicked += (a, b) =>
//            {
//                Directory.CreateDirectory(folder);
//                var fc = new FileChooserNative("Save", window, FileChooserAction.Save, "Save", "Cancel")
//                {
//                    CurrentName = name,
//                    DoOverwriteConfirmation = true
//                };
//                var filter = new FileFilter() { Name = "All files" };
//                filter.AddPattern("*");
//                fc.AddFilter(filter);
//                fc.SetCurrentFolder(folder);
//                if (fc.Run() == (int)Gtk.ResponseType.Accept)
//                {
//                    name = IoPath.GetFileName(fc.Filename);
//                    folder = IoPath.GetDirectoryName(fc.Filename);
//                }
//                fc.Dispose();
//            };

//            btnDownload.Clicked += (a, b) =>
//            {
//                app.StartVideoDownload(id, name, folder, true, null, null, null, 0);
//                window.Dispose();
//            };

//            btnCancel.Clicked += (a, b) =>
//            {
//                window.Dispose();
//            };
//            Application.Invoke((a, b) =>
//            {
//                window.Show();
//            });
//        }

//        //private Toolbar CreateAppToolbar()
//        //{
//        //    var toolbar = new Toolbar();
//        //    toolbar.BorderWidth = 2;
//        //    toolbar.ToolbarStyle = ToolbarStyle.Icons;

//        //    ToolButton newDownloadButton = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "add-line.svg"), 20, 20, true)) { Margin = 5 }, "Delete");
//        //    ToolButton newVideoDwnButton = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "video-download-line.svg"), 20, 20, true)) { Margin = 5 }, "Resume");
//        //    ToolButton pauseButton = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "pause-line.svg"), 20, 20, true)) { Margin = 5 }, "Pause");
//        //    ToolButton resumeButton = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "play-line.svg"), 20, 20, true)) { Margin = 5 }, "Media download");
//        //    ToolButton deleteButton = new ToolButton(new Image(new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "video-download-line.svg"), 20, 20, true)) { Margin = 5 }, "Pause");

//        //    toolbar.Add(newDownloadButton);
//        //    toolbar.Add(newVideoDwnButton);
//        //    toolbar.Add(pauseButton);
//        //    toolbar.Add(resumeButton);
//        //    toolbar.Add(deleteButton);
//        //    toolbar.ShowAll();

//        //    newDownloadButton.Clicked += (a, b) => { ShowNewDownload1Dialog(); };

//        //    return toolbar;
//        //}

//        //private TreeView CreateTreeView2()
//        //{
//        //    TreeView tree = new TreeView();
//        //    tree.StyleContext.AddClass("dark-tree");
//        //    tree.HeadersVisible = false;

//        //    TreeViewColumn languages = new TreeViewColumn();
//        //    languages.Title = "Downloading";

//        //    var fileIconRenderer = new CellRendererPixbuf { Pixbuf = new Gdk.Pixbuf(System.IO.Path.Combine(path, "images", "file-line.svg"), 16, 16, true) /*StockId = Stock.File*/ };
//        //    fileIconRenderer.SetPadding(5,8);
//        //    languages.PackStart(fileIconRenderer, false);

//        //    CellRendererText cell = new CellRendererText();
//        //    cell.SetPadding(5,8);
//        //    languages.PackStart(cell, true);
//        //    languages.AddAttribute(cell, "text", 0);

//        //    TreeStore treestore = new TreeStore(typeof(string));

//        //    TreeIter iter = treestore.AppendValues("Incomplete");
//        //    //treestore.AppendValues(iter, "Python");
//        //    //treestore.AppendValues(iter, "PHP");
//        //    //treestore.AppendValues(iter, "Perl");
//        //    //treestore.AppendValues(iter, "Ruby");

//        //    iter = treestore.AppendValues("Complete");
//        //    treestore.AppendValues(iter, "C#");
//        //    treestore.AppendValues(iter, "C++");
//        //    treestore.AppendValues(iter, "C");
//        //    treestore.AppendValues(iter, "Java");

//        //    tree.AppendColumn(languages);
//        //    tree.Model = treestore;

//        //    tree.ShowAll();

//        //    return tree;
//        //}

//        private void LoadFileIcons()
//        {
//            var pixDocSmall = new Pixbuf(IoPath.Combine(path, "images", "file-text-line.svg"), 24, 24, true);
//            var pixMusicSmall = new Pixbuf(IoPath.Combine(path, "images", "music-2-line.svg"), 24, 24, true);
//            var pixVideoSmall = new Pixbuf(IoPath.Combine(path, "images", "film-line.svg"), 24, 24, true);
//            var pixZipSmall = new Pixbuf(IoPath.Combine(path, "images", "file-zip-line.svg"), 24, 24, true);
//            var pixAppsSmall = new Pixbuf(IoPath.Combine(path, "images", "apps-line.svg"), 24, 24, true);
//            var pixImgSmall = new Pixbuf(IoPath.Combine(path, "images", "image-line.svg"), 24, 24, true);
//            pixOtherSmall = new Pixbuf(IoPath.Combine(path, "images", "file-line.svg"), 16, 16, true);
//            fileIconsSmall = new Dictionary<string, Pixbuf>
//            {
//                ["MP4"] = pixVideoSmall,
//                ["MKV"] = pixVideoSmall,
//                ["WEBM"] = pixVideoSmall,
//                ["TS"] = pixVideoSmall,
//                ["MOV"] = pixVideoSmall,
//                ["MP3"] = pixMusicSmall,
//                ["M4A"] = pixMusicSmall,
//                ["AAC"] = pixMusicSmall,
//                ["OGG"] = pixMusicSmall,
//                ["ZIP"] = pixZipSmall,
//                ["BZ2"] = pixZipSmall,
//                ["TBZ"] = pixZipSmall,
//                ["XZ"] = pixZipSmall,
//                ["TAR"] = pixZipSmall,
//                ["7Z"] = pixZipSmall,
//                ["EXE"] = pixAppsSmall,
//                ["JAR"] = pixAppsSmall,
//                ["MSI"] = pixAppsSmall,
//                ["JPG"] = pixVideoSmall,
//                ["JPEG"] = pixVideoSmall,
//                ["GIF"] = pixVideoSmall,
//                ["PNG"] = pixVideoSmall,
//                ["DOCX"] = pixDocSmall,
//                ["DOC"] = pixDocSmall,
//                ["PDF"] = pixDocSmall,
//                ["ODT"] = pixDocSmall
//            };



//        }

//        void GetFileIcon(ICellLayout cell_layout,
//                CellRenderer cell, ITreeModel tree_model, TreeIter iter)
//        {
//            var name = (string)tree_model.GetValue(iter, FILE_NAME);
//            var ext = IoPath.GetExtension(name)?.ToUpperInvariant();
//            var pix = ext == null ? pixOtherSmall : fileIconsSmall.GetValueOrDefault(ext.Length > 0 ? ext.Substring(1) : string.Empty, pixOtherSmall);
//            (cell as CellRendererPixbuf).Pixbuf = pix;
//        }

//        public void RefereshListView()
//        {
//            filter?.Refilter();
//        }

//        public class RowRef : RowItem
//        {
//            public TreeIter TreeIter { get; set; }
//        }
//    }


//}
