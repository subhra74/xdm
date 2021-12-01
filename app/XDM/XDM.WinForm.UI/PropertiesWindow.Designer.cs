
namespace XDM.WinForm.UI
{
    partial class PropertiesWindow
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.tableLayoutPanel1 = new System.Windows.Forms.TableLayoutPanel();
            this.lblFile = new System.Windows.Forms.Label();
            this.txtFile = new System.Windows.Forms.TextBox();
            this.txtFolder = new System.Windows.Forms.TextBox();
            this.txtUrl = new System.Windows.Forms.TextBox();
            this.lblFolder = new System.Windows.Forms.Label();
            this.lblUrl = new System.Windows.Forms.Label();
            this.lblSize = new System.Windows.Forms.Label();
            this.lblDate = new System.Windows.Forms.Label();
            this.lblType = new System.Windows.Forms.Label();
            this.lblReferer = new System.Windows.Forms.Label();
            this.lblCookies = new System.Windows.Forms.Label();
            this.lblSizeValue = new System.Windows.Forms.Label();
            this.lblDateValue = new System.Windows.Forms.Label();
            this.lblTypeValue = new System.Windows.Forms.Label();
            this.txtReferer = new System.Windows.Forms.TextBox();
            this.lvCookies = new System.Windows.Forms.ListView();
            this.columnHeader1 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader2 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.lblHeader = new System.Windows.Forms.Label();
            this.lvHeaders = new System.Windows.Forms.ListView();
            this.columnHeader3 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.columnHeader4 = ((System.Windows.Forms.ColumnHeader)(new System.Windows.Forms.ColumnHeader()));
            this.tableLayoutPanel1.SuspendLayout();
            this.SuspendLayout();
            // 
            // tableLayoutPanel1
            // 
            this.tableLayoutPanel1.ColumnCount = 2;
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle(System.Windows.Forms.SizeType.Percent, 100F));
            this.tableLayoutPanel1.Controls.Add(this.lblFile, 0, 0);
            this.tableLayoutPanel1.Controls.Add(this.txtFile, 0, 1);
            this.tableLayoutPanel1.Controls.Add(this.txtFolder, 0, 3);
            this.tableLayoutPanel1.Controls.Add(this.txtUrl, 0, 5);
            this.tableLayoutPanel1.Controls.Add(this.lblFolder, 0, 2);
            this.tableLayoutPanel1.Controls.Add(this.lblUrl, 0, 4);
            this.tableLayoutPanel1.Controls.Add(this.lblSize, 0, 6);
            this.tableLayoutPanel1.Controls.Add(this.lblDate, 0, 7);
            this.tableLayoutPanel1.Controls.Add(this.lblType, 0, 8);
            this.tableLayoutPanel1.Controls.Add(this.lblReferer, 0, 9);
            this.tableLayoutPanel1.Controls.Add(this.lblCookies, 0, 11);
            this.tableLayoutPanel1.Controls.Add(this.lblSizeValue, 1, 6);
            this.tableLayoutPanel1.Controls.Add(this.lblDateValue, 1, 7);
            this.tableLayoutPanel1.Controls.Add(this.lblTypeValue, 1, 8);
            this.tableLayoutPanel1.Controls.Add(this.txtReferer, 0, 10);
            this.tableLayoutPanel1.Controls.Add(this.lvCookies, 0, 12);
            this.tableLayoutPanel1.Controls.Add(this.lblHeader, 0, 13);
            this.tableLayoutPanel1.Controls.Add(this.lvHeaders, 0, 14);
            this.tableLayoutPanel1.Dock = System.Windows.Forms.DockStyle.Fill;
            this.tableLayoutPanel1.Location = new System.Drawing.Point(0, 0);
            this.tableLayoutPanel1.Name = "tableLayoutPanel1";
            this.tableLayoutPanel1.Padding = new System.Windows.Forms.Padding(10);
            this.tableLayoutPanel1.RowCount = 15;
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle(System.Windows.Forms.SizeType.Percent, 50F));
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle(System.Windows.Forms.SizeType.Percent, 50F));
            this.tableLayoutPanel1.Size = new System.Drawing.Size(469, 511);
            this.tableLayoutPanel1.TabIndex = 0;
            // 
            // lblFile
            // 
            this.lblFile.AutoSize = true;
            this.lblFile.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblFile.Location = new System.Drawing.Point(13, 10);
            this.lblFile.Name = "lblFile";
            this.lblFile.Padding = new System.Windows.Forms.Padding(0, 5, 0, 0);
            this.lblFile.Size = new System.Drawing.Size(53, 20);
            this.lblFile.TabIndex = 0;
            this.lblFile.Text = "File";
            // 
            // txtFile
            // 
            this.txtFile.BackColor = System.Drawing.Color.White;
            this.tableLayoutPanel1.SetColumnSpan(this.txtFile, 2);
            this.txtFile.Dock = System.Windows.Forms.DockStyle.Fill;
            this.txtFile.Location = new System.Drawing.Point(13, 33);
            this.txtFile.Name = "txtFile";
            this.txtFile.ReadOnly = true;
            this.txtFile.Size = new System.Drawing.Size(443, 23);
            this.txtFile.TabIndex = 1;
            // 
            // txtFolder
            // 
            this.txtFolder.BackColor = System.Drawing.Color.White;
            this.tableLayoutPanel1.SetColumnSpan(this.txtFolder, 2);
            this.txtFolder.Dock = System.Windows.Forms.DockStyle.Fill;
            this.txtFolder.Location = new System.Drawing.Point(13, 82);
            this.txtFolder.Name = "txtFolder";
            this.txtFolder.ReadOnly = true;
            this.txtFolder.Size = new System.Drawing.Size(443, 23);
            this.txtFolder.TabIndex = 2;
            // 
            // txtUrl
            // 
            this.txtUrl.BackColor = System.Drawing.Color.White;
            this.tableLayoutPanel1.SetColumnSpan(this.txtUrl, 2);
            this.txtUrl.Dock = System.Windows.Forms.DockStyle.Fill;
            this.txtUrl.Location = new System.Drawing.Point(13, 131);
            this.txtUrl.Name = "txtUrl";
            this.txtUrl.ReadOnly = true;
            this.txtUrl.Size = new System.Drawing.Size(443, 23);
            this.txtUrl.TabIndex = 3;
            // 
            // lblFolder
            // 
            this.lblFolder.AutoSize = true;
            this.lblFolder.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblFolder.Location = new System.Drawing.Point(13, 59);
            this.lblFolder.Name = "lblFolder";
            this.lblFolder.Padding = new System.Windows.Forms.Padding(0, 5, 0, 0);
            this.lblFolder.Size = new System.Drawing.Size(53, 20);
            this.lblFolder.TabIndex = 4;
            this.lblFolder.Text = "Location";
            // 
            // lblUrl
            // 
            this.lblUrl.AutoSize = true;
            this.lblUrl.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblUrl.Location = new System.Drawing.Point(13, 108);
            this.lblUrl.Name = "lblUrl";
            this.lblUrl.Padding = new System.Windows.Forms.Padding(0, 5, 0, 0);
            this.lblUrl.Size = new System.Drawing.Size(53, 20);
            this.lblUrl.TabIndex = 5;
            this.lblUrl.Text = "Address";
            // 
            // lblSize
            // 
            this.lblSize.AutoSize = true;
            this.lblSize.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblSize.Location = new System.Drawing.Point(13, 157);
            this.lblSize.Name = "lblSize";
            this.lblSize.Padding = new System.Windows.Forms.Padding(0, 5, 0, 5);
            this.lblSize.Size = new System.Drawing.Size(53, 25);
            this.lblSize.TabIndex = 6;
            this.lblSize.Text = "Size";
            this.lblSize.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblDate
            // 
            this.lblDate.AutoSize = true;
            this.lblDate.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblDate.Location = new System.Drawing.Point(13, 182);
            this.lblDate.Name = "lblDate";
            this.lblDate.Padding = new System.Windows.Forms.Padding(0, 5, 0, 5);
            this.lblDate.Size = new System.Drawing.Size(53, 25);
            this.lblDate.TabIndex = 7;
            this.lblDate.Text = "Date";
            this.lblDate.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblType
            // 
            this.lblType.AutoSize = true;
            this.lblType.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblType.Location = new System.Drawing.Point(13, 207);
            this.lblType.Name = "lblType";
            this.lblType.Padding = new System.Windows.Forms.Padding(0, 5, 0, 5);
            this.lblType.Size = new System.Drawing.Size(53, 25);
            this.lblType.TabIndex = 8;
            this.lblType.Text = "Type";
            this.lblType.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblReferer
            // 
            this.lblReferer.AutoSize = true;
            this.lblReferer.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblReferer.Location = new System.Drawing.Point(13, 232);
            this.lblReferer.Name = "lblReferer";
            this.lblReferer.Padding = new System.Windows.Forms.Padding(0, 5, 0, 5);
            this.lblReferer.Size = new System.Drawing.Size(53, 25);
            this.lblReferer.TabIndex = 9;
            this.lblReferer.Text = "Referer";
            this.lblReferer.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblCookies
            // 
            this.lblCookies.AutoSize = true;
            this.lblCookies.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblCookies.Location = new System.Drawing.Point(13, 286);
            this.lblCookies.Name = "lblCookies";
            this.lblCookies.Padding = new System.Windows.Forms.Padding(0, 5, 0, 5);
            this.lblCookies.Size = new System.Drawing.Size(53, 25);
            this.lblCookies.TabIndex = 10;
            this.lblCookies.Text = "Cookies";
            this.lblCookies.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblSizeValue
            // 
            this.lblSizeValue.AutoSize = true;
            this.lblSizeValue.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblSizeValue.Location = new System.Drawing.Point(72, 157);
            this.lblSizeValue.Name = "lblSizeValue";
            this.lblSizeValue.Size = new System.Drawing.Size(384, 25);
            this.lblSizeValue.TabIndex = 12;
            this.lblSizeValue.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblDateValue
            // 
            this.lblDateValue.AutoSize = true;
            this.lblDateValue.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblDateValue.Location = new System.Drawing.Point(72, 182);
            this.lblDateValue.Name = "lblDateValue";
            this.lblDateValue.Size = new System.Drawing.Size(384, 25);
            this.lblDateValue.TabIndex = 13;
            this.lblDateValue.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lblTypeValue
            // 
            this.lblTypeValue.AutoSize = true;
            this.lblTypeValue.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblTypeValue.Location = new System.Drawing.Point(72, 207);
            this.lblTypeValue.Name = "lblTypeValue";
            this.lblTypeValue.Size = new System.Drawing.Size(384, 25);
            this.lblTypeValue.TabIndex = 14;
            this.lblTypeValue.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // txtReferer
            // 
            this.txtReferer.BackColor = System.Drawing.Color.White;
            this.tableLayoutPanel1.SetColumnSpan(this.txtReferer, 2);
            this.txtReferer.Dock = System.Windows.Forms.DockStyle.Fill;
            this.txtReferer.Location = new System.Drawing.Point(13, 260);
            this.txtReferer.Name = "txtReferer";
            this.txtReferer.ReadOnly = true;
            this.txtReferer.Size = new System.Drawing.Size(443, 23);
            this.txtReferer.TabIndex = 18;
            // 
            // lvCookies
            // 
            this.lvCookies.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader1,
            this.columnHeader2});
            this.tableLayoutPanel1.SetColumnSpan(this.lvCookies, 2);
            this.lvCookies.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lvCookies.HideSelection = false;
            this.lvCookies.Location = new System.Drawing.Point(13, 314);
            this.lvCookies.Name = "lvCookies";
            this.lvCookies.Size = new System.Drawing.Size(443, 76);
            this.lvCookies.TabIndex = 19;
            this.lvCookies.UseCompatibleStateImageBehavior = false;
            this.lvCookies.View = System.Windows.Forms.View.Details;
            // 
            // columnHeader1
            // 
            this.columnHeader1.Text = "Name";
            // 
            // columnHeader2
            // 
            this.columnHeader2.Text = "Value";
            // 
            // lblHeader
            // 
            this.lblHeader.AutoSize = true;
            this.lblHeader.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblHeader.Location = new System.Drawing.Point(13, 393);
            this.lblHeader.Name = "lblHeader";
            this.lblHeader.Padding = new System.Windows.Forms.Padding(0, 5, 0, 5);
            this.lblHeader.Size = new System.Drawing.Size(53, 25);
            this.lblHeader.TabIndex = 20;
            this.lblHeader.Text = "Headers";
            this.lblHeader.TextAlign = System.Drawing.ContentAlignment.MiddleLeft;
            // 
            // lvHeaders
            // 
            this.lvHeaders.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] {
            this.columnHeader3,
            this.columnHeader4});
            this.tableLayoutPanel1.SetColumnSpan(this.lvHeaders, 2);
            this.lvHeaders.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lvHeaders.HideSelection = false;
            this.lvHeaders.Location = new System.Drawing.Point(13, 421);
            this.lvHeaders.Name = "lvHeaders";
            this.lvHeaders.Size = new System.Drawing.Size(443, 77);
            this.lvHeaders.TabIndex = 21;
            this.lvHeaders.UseCompatibleStateImageBehavior = false;
            this.lvHeaders.View = System.Windows.Forms.View.Details;
            // 
            // columnHeader3
            // 
            this.columnHeader3.Text = "Name";
            // 
            // columnHeader4
            // 
            this.columnHeader4.Text = "Value";
            // 
            // Win32PropertiesWindow
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(96F, 96F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Dpi;
            this.AutoScroll = true;
            this.BackColor = System.Drawing.Color.White;
            this.ClientSize = new System.Drawing.Size(469, 511);
            this.Controls.Add(this.tableLayoutPanel1);
            this.Font = new System.Drawing.Font("Segoe UI", 9F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(0)));
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.Name = "Win32PropertiesWindow";
            this.ShowIcon = false;
            this.ShowInTaskbar = false;
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterScreen;
            this.Text = "Properties";
            this.tableLayoutPanel1.ResumeLayout(false);
            this.tableLayoutPanel1.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.TableLayoutPanel tableLayoutPanel1;
        private System.Windows.Forms.Label lblFile;
        private System.Windows.Forms.TextBox txtFile;
        private System.Windows.Forms.TextBox txtFolder;
        private System.Windows.Forms.TextBox txtUrl;
        private System.Windows.Forms.Label lblFolder;
        private System.Windows.Forms.Label lblUrl;
        private System.Windows.Forms.Label lblSize;
        private System.Windows.Forms.Label lblDate;
        private System.Windows.Forms.Label lblType;
        private System.Windows.Forms.Label lblReferer;
        private System.Windows.Forms.Label lblSizeValue;
        private System.Windows.Forms.Label lblDateValue;
        private System.Windows.Forms.Label lblTypeValue;
        private System.Windows.Forms.Label lblCookies;
        private System.Windows.Forms.TextBox txtReferer;
        private System.Windows.Forms.ListView lvCookies;
        private System.Windows.Forms.Label lblHeader;
        private System.Windows.Forms.ListView lvHeaders;
        private System.Windows.Forms.ColumnHeader columnHeader1;
        private System.Windows.Forms.ColumnHeader columnHeader2;
        private System.Windows.Forms.ColumnHeader columnHeader3;
        private System.Windows.Forms.ColumnHeader columnHeader4;
    }
}