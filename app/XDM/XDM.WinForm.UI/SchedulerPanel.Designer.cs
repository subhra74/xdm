
namespace XDM.WinForm.UI
{
    partial class SchedulerPanel
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

        #region Component Designer generated code

        /// <summary> 
        /// Required method for Designer support - do not modify 
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.tableLayoutPanel1 = new System.Windows.Forms.TableLayoutPanel();
            this.dateTimePicker1 = new System.Windows.Forms.DateTimePicker();
            this.dateTimePicker2 = new System.Windows.Forms.DateTimePicker();
            this.chkTue = new System.Windows.Forms.CheckBox();
            this.chkWed = new System.Windows.Forms.CheckBox();
            this.chkThu = new System.Windows.Forms.CheckBox();
            this.chkFri = new System.Windows.Forms.CheckBox();
            this.chkSat = new System.Windows.Forms.CheckBox();
            this.chkSun = new System.Windows.Forms.CheckBox();
            this.chkMon = new System.Windows.Forms.CheckBox();
            this.label1 = new System.Windows.Forms.Label();
            this.label2 = new System.Windows.Forms.Label();
            this.chkEveryday = new System.Windows.Forms.CheckBox();
            this.tableLayoutPanel1.SuspendLayout();
            this.SuspendLayout();
            // 
            // tableLayoutPanel1
            // 
            this.tableLayoutPanel1.BackColor = System.Drawing.Color.White;
            this.tableLayoutPanel1.ColumnCount = 3;
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.ColumnStyles.Add(new System.Windows.Forms.ColumnStyle());
            this.tableLayoutPanel1.Controls.Add(this.dateTimePicker1, 1, 0);
            this.tableLayoutPanel1.Controls.Add(this.dateTimePicker2, 1, 6);
            this.tableLayoutPanel1.Controls.Add(this.chkTue, 1, 3);
            this.tableLayoutPanel1.Controls.Add(this.chkWed, 2, 3);
            this.tableLayoutPanel1.Controls.Add(this.chkThu, 1, 4);
            this.tableLayoutPanel1.Controls.Add(this.chkFri, 2, 4);
            this.tableLayoutPanel1.Controls.Add(this.chkSat, 1, 5);
            this.tableLayoutPanel1.Controls.Add(this.chkSun, 1, 2);
            this.tableLayoutPanel1.Controls.Add(this.chkMon, 2, 2);
            this.tableLayoutPanel1.Controls.Add(this.label1, 0, 6);
            this.tableLayoutPanel1.Controls.Add(this.label2, 0, 0);
            this.tableLayoutPanel1.Controls.Add(this.chkEveryday, 0, 2);
            this.tableLayoutPanel1.Dock = System.Windows.Forms.DockStyle.Fill;
            this.tableLayoutPanel1.Location = new System.Drawing.Point(0, 0);
            this.tableLayoutPanel1.Name = "tableLayoutPanel1";
            this.tableLayoutPanel1.Padding = new System.Windows.Forms.Padding(17);
            this.tableLayoutPanel1.RowCount = 8;
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle());
            this.tableLayoutPanel1.RowStyles.Add(new System.Windows.Forms.RowStyle(System.Windows.Forms.SizeType.Percent, 100F));
            this.tableLayoutPanel1.Size = new System.Drawing.Size(528, 355);
            this.tableLayoutPanel1.TabIndex = 4;
            // 
            // dateTimePicker1
            // 
            this.dateTimePicker1.CustomFormat = "hh:mm";
            this.dateTimePicker1.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.dateTimePicker1.Location = new System.Drawing.Point(96, 20);
            this.dateTimePicker1.Name = "dateTimePicker1";
            this.dateTimePicker1.ShowUpDown = true;
            this.dateTimePicker1.Size = new System.Drawing.Size(101, 20);
            this.dateTimePicker1.TabIndex = 2;
            this.dateTimePicker1.Value = new System.DateTime(2021, 7, 2, 0, 0, 0, 0);
            // 
            // dateTimePicker2
            // 
            this.dateTimePicker2.Format = System.Windows.Forms.DateTimePickerFormat.Time;
            this.dateTimePicker2.Location = new System.Drawing.Point(96, 156);
            this.dateTimePicker2.Name = "dateTimePicker2";
            this.dateTimePicker2.ShowUpDown = true;
            this.dateTimePicker2.Size = new System.Drawing.Size(101, 20);
            this.dateTimePicker2.TabIndex = 4;
            this.dateTimePicker2.Value = new System.DateTime(2021, 7, 2, 0, 0, 0, 0);
            // 
            // chkTue
            // 
            this.chkTue.AutoSize = true;
            this.chkTue.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkTue.Location = new System.Drawing.Point(96, 78);
            this.chkTue.Name = "chkTue";
            this.chkTue.Size = new System.Drawing.Size(101, 17);
            this.chkTue.TabIndex = 11;
            this.chkTue.Text = "Tuesday";
            this.chkTue.UseVisualStyleBackColor = true;
            // 
            // chkWed
            // 
            this.chkWed.AutoSize = true;
            this.chkWed.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkWed.Location = new System.Drawing.Point(203, 78);
            this.chkWed.Name = "chkWed";
            this.chkWed.Size = new System.Drawing.Size(305, 17);
            this.chkWed.TabIndex = 12;
            this.chkWed.Text = "Wednesday";
            this.chkWed.UseVisualStyleBackColor = true;
            // 
            // chkThu
            // 
            this.chkThu.AutoSize = true;
            this.chkThu.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkThu.Location = new System.Drawing.Point(96, 101);
            this.chkThu.Name = "chkThu";
            this.chkThu.Size = new System.Drawing.Size(101, 17);
            this.chkThu.TabIndex = 14;
            this.chkThu.Text = "Thursday";
            this.chkThu.UseVisualStyleBackColor = true;
            // 
            // chkFri
            // 
            this.chkFri.AutoSize = true;
            this.chkFri.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkFri.Location = new System.Drawing.Point(203, 101);
            this.chkFri.Name = "chkFri";
            this.chkFri.Size = new System.Drawing.Size(305, 17);
            this.chkFri.TabIndex = 15;
            this.chkFri.Text = "Friday";
            this.chkFri.UseVisualStyleBackColor = true;
            // 
            // chkSat
            // 
            this.chkSat.AutoSize = true;
            this.chkSat.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkSat.Location = new System.Drawing.Point(96, 124);
            this.chkSat.Name = "chkSat";
            this.chkSat.Padding = new System.Windows.Forms.Padding(0, 0, 0, 9);
            this.chkSat.Size = new System.Drawing.Size(101, 26);
            this.chkSat.TabIndex = 16;
            this.chkSat.Text = "Saturday";
            this.chkSat.UseVisualStyleBackColor = true;
            // 
            // chkSun
            // 
            this.chkSun.AutoSize = true;
            this.chkSun.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkSun.Location = new System.Drawing.Point(96, 46);
            this.chkSun.Name = "chkSun";
            this.chkSun.Padding = new System.Windows.Forms.Padding(0, 9, 0, 0);
            this.chkSun.Size = new System.Drawing.Size(101, 26);
            this.chkSun.TabIndex = 17;
            this.chkSun.Text = "Sunday";
            this.chkSun.UseVisualStyleBackColor = true;
            // 
            // chkMon
            // 
            this.chkMon.AutoSize = true;
            this.chkMon.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkMon.Location = new System.Drawing.Point(203, 46);
            this.chkMon.Name = "chkMon";
            this.chkMon.Padding = new System.Windows.Forms.Padding(0, 9, 0, 0);
            this.chkMon.Size = new System.Drawing.Size(305, 26);
            this.chkMon.TabIndex = 18;
            this.chkMon.Text = "Monday";
            this.chkMon.UseVisualStyleBackColor = true;
            // 
            // label1
            // 
            this.label1.AutoSize = true;
            this.label1.Dock = System.Windows.Forms.DockStyle.Fill;
            this.label1.Location = new System.Drawing.Point(20, 153);
            this.label1.Name = "label1";
            this.label1.Size = new System.Drawing.Size(70, 26);
            this.label1.TabIndex = 19;
            this.label1.Text = "Stop at";
            this.label1.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // label2
            // 
            this.label2.AutoSize = true;
            this.label2.Dock = System.Windows.Forms.DockStyle.Fill;
            this.label2.Location = new System.Drawing.Point(20, 17);
            this.label2.Name = "label2";
            this.label2.Size = new System.Drawing.Size(70, 26);
            this.label2.TabIndex = 20;
            this.label2.Text = "Start at";
            this.label2.TextAlign = System.Drawing.ContentAlignment.MiddleRight;
            // 
            // chkEveryday
            // 
            this.chkEveryday.AutoSize = true;
            this.chkEveryday.Dock = System.Windows.Forms.DockStyle.Fill;
            this.chkEveryday.Location = new System.Drawing.Point(20, 46);
            this.chkEveryday.Name = "chkEveryday";
            this.chkEveryday.Padding = new System.Windows.Forms.Padding(0, 9, 0, 0);
            this.chkEveryday.Size = new System.Drawing.Size(70, 26);
            this.chkEveryday.TabIndex = 21;
            this.chkEveryday.Text = "Everyday";
            this.chkEveryday.UseVisualStyleBackColor = true;
            this.chkEveryday.CheckedChanged += new System.EventHandler(this.chkEveryday_CheckedChanged);
            // 
            // SchedulerPanel
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(96F, 96F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Dpi;
            this.BackColor = System.Drawing.Color.White;
            this.Controls.Add(this.tableLayoutPanel1);
            this.Name = "SchedulerPanel";
            this.Size = new System.Drawing.Size(528, 355);
            this.tableLayoutPanel1.ResumeLayout(false);
            this.tableLayoutPanel1.PerformLayout();
            this.ResumeLayout(false);

        }

        #endregion

        private System.Windows.Forms.TableLayoutPanel tableLayoutPanel1;
        private System.Windows.Forms.DateTimePicker dateTimePicker1;
        private System.Windows.Forms.DateTimePicker dateTimePicker2;
        private System.Windows.Forms.CheckBox chkTue;
        private System.Windows.Forms.CheckBox chkWed;
        private System.Windows.Forms.CheckBox chkThu;
        private System.Windows.Forms.CheckBox chkFri;
        private System.Windows.Forms.CheckBox chkSat;
        private System.Windows.Forms.CheckBox chkSun;
        private System.Windows.Forms.CheckBox chkMon;
        private System.Windows.Forms.Label label1;
        private System.Windows.Forms.Label label2;
        private System.Windows.Forms.CheckBox chkEveryday;
    }
}
