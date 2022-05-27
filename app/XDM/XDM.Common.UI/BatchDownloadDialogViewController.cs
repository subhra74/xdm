using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;

namespace XDM.Common.UI
{
    public class BatchDownloadDialogViewController
    {
        private IBatchDownloadDialogView view;
        public IAppUI AppUI { get; set; }
        public IApp App { get; set; }

        public BatchDownloadDialogViewController(IBatchDownloadDialogView view, IApp app, IAppUI appUI)
        {
            this.view = view;
            this.AppUI = appUI;
            this.App = app;
        }

        public void ShowWindow(object parent)
        {

        }
    }
}
