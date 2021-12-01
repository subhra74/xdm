
namespace XDM.WinForm.UI
{
    partial class NewVideoDownloadDialogView
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
            this.components = new System.ComponentModel.Container();
            this.tableLayoutPanel1 = new System.Windows.Forms.TableLayoutPanel();
            this.btnCancel = new System.Windows.Forms.Button();
            this.btnLater = new System.Windows.Forms.Button();
            this.btnDownload = new System.Windows.Forms.Button();
            this.tableLayoutPanel2 = new System.Windows.Forms.TableLayoutPanel();
            this.lblAddress = new System.Windows.Forms.Label();
            this.lblFile = new System.Windows.Forms.Label();
            this.lblFileIcon = new System.Windows.Forms.Label();
            this.lblFileSize = new System.Windows.Forms.Label();
            this.txtFileName = new System.Windows.Forms.TextBox();
            this.contextMenuStrip1 = new System.Windows.Forms.ContextMenuStrip(this.components);
            this.doNotAddToQueueToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
            this.manageQueueAndSchedulerToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
            this.tableLayoutPanel1.SuspendLayout();
            this.tableLayoutPanel2.SuspendLayout();
            this.contextMenuStrip1.SuspendLayout();
            this.SuspendLayout();
            // 
            // tableLayoutPanel1
            // 
            this.tableLayoutPanel1.AutoSize = true;
            this.tableLayoutPanel1.AutoSizeMode = System.Windows.Forms.AutoSizeMode.GrowAndShrink;
            this.tableLayoutPanel1.ColumnCount = 4;
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle(System.Windows.Forms.SizeType.Percent, 100F));
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.Controls.Add(this.btnCancel, 0, 0);
            this.tableLayoutPanel1.Controls.Add(this.btnLater, 2, 0);
            this.tableLayoutPanel1.Controls.Add(this.btnDownload, 3, 0);
            this.tableLayoutPanel1.Dock = System.Windows.Forms.DockStyle.Bottom;
            this.tableLayoutPanel1.Location = new System.Drawing.Point(0, 107);
            this.tableLayoutPanel1.Name = "tableLayoutPanel1";
            this.tableLayoutPanel1.Padding = new System.Windows.Forms.Padding(10);
            this.tableLayoutPanel1.RowCount = 1;
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.Size = new System.Drawing.Size(419, 57);
            this.tableLayoutPanel1.TabIndex = 4;
            // 
            // btnCancel
            // 
            this.btnCancel.AutoSize = true;
            this.btnCancel.AutoSizeMode = System.Windows.Forms.AutoSizeMode.GrowAndShrink;
            this.btnCancel.Location = new System.Drawing.Point(13, 13);
            this.btnCancel.Name = "btnCancel";
            this.btnCancel.Padding = new System.Windows.Forms.Padding(10, 3, 10, 3);
            this.btnCancel.Size = new System.Drawing.Size(90, 31);
            this.btnCancel.TabIndex = 2;
            this.btnCancel.Text = "Advanced";
            this.btnCancel.UseVisualStyleBackColor = true;
            this.btnCancel.Click += new System.EventHandler(this.btnCancel_Click);
            // 
            // btnLater
            // 
            this.btnLater.AutoSize = true;
            this.btnLater.AutoSizeMode = System.Windows.Forms.AutoSizeMode.GrowAndShrink;
            this.btnLater.Location = new System.Drawing.Point(161, 13);
            this.btnLater.Name = "btnLater";
            this.btnLater.Padding = new System.Windows.Forms.Padding(10, 3, 10, 3);
            this.btnLater.Size = new System.Drawing.Size(120, 31);
            this.btnLater.TabIndex = 1;
            this.btnLater.Text = "Download Later";
            this.btnLater.UseVisualStyleBackColor = true;
            this.btnLater.Click += new System.EventHandler(this.btnDownloadLater_Click);
            // 
            // btnDownload
            // 
            this.btnDownload.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Right)));
            this.btnDownload.AutoSize = true;
            this.btnDownload.AutoSizeMode = System.Windows.Forms.AutoSizeMode.GrowAndShrink;
            this.btnDownload.Location = new System.Drawing.Point(287, 13);
            this.btnDownload.Name = "btnDownload";
            this.btnDownload.Padding = new System.Windows.Forms.Padding(10, 3, 10, 3);
            this.btnDownload.Size = new System.Drawing.Size(119, 31);
            this.btnDownload.TabIndex = 0;
            this.btnDownload.Text = "Download Now";
            this.btnDownload.UseVisualStyleBackColor = true;
            this.btnDownload.Click += new System.EventHandler(this.btnDownload_Click);
            // 
            // tableLayoutPanel2
            // 
            this.tableLayoutPanel2.BackColor = System.Drawing.Color.White;
            this.tableLayoutPanel2.ColumnCount = 4;
            this.tableLayoutPanel2.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel2.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle(System.Windows.Forms.SizeType.Percent, 100F));
            this.tableLayoutPanel2.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel2.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel2.Controls.Add(this.lblAddress, 0, 0);
            this.tableLayoutPanel2.Controls.Add(this.lblFile, 0, 1);
            this.tableLayoutPanel2.Controls.Add(this.lblFileIcon, 3, 0);
            this.tableLayoutPanel2.Controls.Add(this.lblFileSize, 3, 2);
            this.tableLayoutPanel2.Controls.Add(this.txtFileName, 1, 0);
            this.tableLayoutPanel2.Dock = System.Windows.Forms.DockStyle.Fill;
            this.tableLayoutPanel2.Location = new System.Drawing.Point(0, 0);
            this.tableLayoutPanel2.Name = "tableLayoutPanel2";
            this.tableLayoutPanel2.Padding = new System.Windows.Forms.Padding(10);
            this.tableLayoutPanel2.RowCount = 3;
            this.tableLayoutPanel2.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel2.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel2.RowStyles.Add(new System.Windows.Forms.RowStyle(System.Windows.Forms.SizeType.Percent, 100F));
            this.tableLayoutPanel2.Size = new System.Drawing.Size(419, 107);
            this.tableLayoutPanel2.TabIndex = 5;
            // 
            // lblAddress
            // 
            this.lblAddress.AutoSize = true;
            this.lblAddress.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblAddress.Location = new System.Drawing.Point(13, 10);
            this.lblAddress.Name = "lblAddress";
            this.lblAddress.Padding = new System.Windows.Forms.Padding(15, 5, 5, 5);
            this.lblAddress.Size = new System.Drawing.Size(64, 29);
            this.lblAddress.TabIndex = 0;
            this.lblAddress.Text = "Name";
            this.lblAddress.TextAlign = System.Drawing.ContentAlignment.TopRight;
            // 
            // lblFile
            // 
            this.lblFile.AutoSize = true;
            this.lblFile.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblFile.Location = new System.Drawing.Point(13, 39);
            this.lblFile.Name = "lblFile";
            this.lblFile.Padding = new System.Windows.Forms.Padding(15, 5, 5, 5);
            this.lblFile.Size = new System.Drawing.Size(64, 25);
            this.lblFile.TabIndex = 1;
            this.lblFile.Text = "Save In";
            this.lblFile.TextAlign = System.Drawing.ContentAlignment.TopRight;
            // 
            // lblFileIcon
            // 
            this.lblFileIcon.AutoSize = true;
            this.lblFileIcon.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblFileIcon.ForeColor = System.Drawing.Color.Silver;
            this.lblFileIcon.Location = new System.Drawing.Point(358, 10);
            this.lblFileIcon.Name = "lblFileIcon";
            this.lblFileIcon.Padding = new System.Windows.Forms.Padding(5);
            this.tableLayoutPanel2.SetRowSpan(this.lblFileIcon, 2);
            this.lblFileIcon.Size = new System.Drawing.Size(48, 54);
            this.lblFileIcon.TabIndex = 5;
            this.lblFileIcon.Text = "label5";
            this.lblFileIcon.TextAlign = System.Drawing.ContentAlignment.MiddleCenter;
            // 
            // lblFileSize
            // 
            this.lblFileSize.AutoSize = true;
            this.lblFileSize.Dock = System.Windows.Forms.DockStyle.Fill;
            this.lblFileSize.Location = new System.Drawing.Point(358, 64);
            this.lblFileSize.Name = "lblFileSize";
            this.lblFileSize.Size = new System.Drawing.Size(48, 33);
            this.lblFileSize.TabIndex = 6;
            this.lblFileSize.Text = "label6";
            this.lblFileSize.TextAlign = System.Drawing.ContentAlignment.TopCenter;
            // 
            // txtFileName
            // 
            this.tableLayoutPanel2.SetColumnSpan(this.txtFileName, 2);
            this.txtFileName.Dock = System.Windows.Forms.DockStyle.Fill;
            this.txtFileName.Location = new System.Drawing.Point(83, 13);
            this.txtFileName.Name = "txtFileName";
            this.txtFileName.Size = new System.Drawing.Size(269, 23);
            this.txtFileName.TabIndex = 7;
            // 
            // contextMenuStrip1
            // 
            this.contextMenuStrip1.Items.AddRange(new System.Windows.Forms.ToolStripItem[] {
            this.doNotAddToQueueToolStripMenuItem,
            this.manageQueueAndSchedulerToolStripMenuItem});
            this.contextMenuStrip1.Name = "contextMenuStrip1";
            this.contextMenuStrip1.Size = new System.Drawing.Size(231, 48);
            // 
            // doNotAddToQueueToolStripMenuItem
            // 
            this.doNotAddToQueueToolStripMenuItem.Name = "doNotAddToQueueToolStripMenuItem";
            this.doNotAddToQueueToolStripMenuItem.Size = new System.Drawing.Size(230, 22);
            this.doNotAddToQueueToolStripMenuItem.Text = "Do not add to queue";
            this.doNotAddToQueueToolStripMenuItem.Click += new System.EventHandler(this.doNotAddToQueueToolStripMenuItem_Click);
            // 
            // manageQueueAndSchedulerToolStripMenuItem
            // 
            this.manageQueueAndSchedulerToolStripMenuItem.Name = "manageQueueAndSchedulerToolStripMenuItem";
            this.manageQueueAndSchedulerToolStripMenuItem.Size = new System.Drawing.Size(230, 22);
            this.manageQueueAndSchedulerToolStripMenuItem.Text = "Manage queue and scheduler";
            this.manageQueueAndSchedulerToolStripMenuItem.Click += new System.EventHandler(this.manageQueueAndSchedulerToolStripMenuItem_Click);
            // 
            // NewVideoDownloadDialogView
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(96F, 96F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Dpi;
            this.ClientSize = new System.Drawing.Size(419, 164);
            this.Controls.Add(this.tableLayoutPanel2);
            this.Controls.Add(this.tableLayoutPanel1);
            this.Font = new System.Drawing.Font("Segoe UI", 9F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(0)));
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.Name = "NewVideoDownloadDialogView";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterScreen;
            this.Text = "New Download";
            this.TopMost = true;
            this.tableLayoutPanel1.ResumeLayout(false);
            this.tableLayoutPanel1.PerformLayout();
            this.tableLayoutPanel2.ResumeLayout(false);
            this.tableLayoutPanel2.PerformLayout();
            this.contextMenuStrip1.ResumeLayout(false);
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion

        private System.Windows.Forms.TableLayoutPanel tableLayoutPanel1;
        private System.Windows.Forms.Button btnCancel;
        private System.Windows.Forms.Button btnDownload;
        private System.Windows.Forms.Button btnLater;
        private System.Windows.Forms.TableLayoutPanel tableLayoutPanel2;
        private System.Windows.Forms.Label lblAddress;
        private System.Windows.Forms.Label lblFile;
        private System.Windows.Forms.Label lblFileIcon;
        private System.Windows.Forms.Label lblFileSize;
        private System.Windows.Forms.TextBox txtFileName;
        private System.Windows.Forms.ContextMenuStrip contextMenuStrip1;
        private System.Windows.Forms.ToolStripMenuItem doNotAddToQueueToolStripMenuItem;
        private System.Windows.Forms.ToolStripMenuItem manageQueueAndSchedulerToolStripMenuItem;
    }
}