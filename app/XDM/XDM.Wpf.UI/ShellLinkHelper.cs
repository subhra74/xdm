//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Runtime.InteropServices;
//using System.Text;
//using System.Threading.Tasks;

//namespace XDM.Wpf.UI
//{
//    internal class ShellLinkHelper
//    {
//        internal static void CreateLink(string sourcePath, string targetPath)
//        {
//            IShellLinkW link = (IShellLinkW)new ShellLink();

//            // setup shortcut information
//            link.SetDescription("XDM chrome extension");
//            //link.SetIconLocation(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app"), 0);
//            link.SetPath(sourcePath);

//            // save it
//            IPersistFile file = (IPersistFile)link;
//            file.Save(Path.Combine(targetPath, "chrome-extension.lnk"), false);
//        }

//        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
//        public class ShellLink
//        {
//            // Making new of this class will call CoCreate e.g. new ShellLink();
//            // Cast to one of the interfaces below will QueryInterface. e.g. (IPersistFile)new ShellLink();
//        }

//        // http://msdn.microsoft.com/en-us/library/windows/desktop/bb774950(v=vs.85).aspx
//        // http://pinvoke.net/default.aspx/Interfaces/IShellLinkW.html
//        /// <summary>The IShellLink interface allows Shell links to be created, modified, and resolved</summary>
//        [ComImport(), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
//        public interface IShellLinkW
//        {
//            /// <summary>Retrieves the path and file name of a Shell link object</summary>
//            void GetPath([Out(), MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cchMaxPath, out IntPtr pfd, int fFlags);
//            /// <summary>Retrieves the list of item identifiers for a Shell link object</summary>
//            void GetIDList(out IntPtr ppidl);
//            /// <summary>Sets the pointer to an item identifier list (PIDL) for a Shell link object.</summary>
//            void SetIDList(IntPtr pidl);
//            /// <summary>Retrieves the description string for a Shell link object</summary>
//            void GetDescription([Out(), MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cchMaxName);
//            /// <summary>Sets the description for a Shell link object. The description can be any application-defined string</summary>
//            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
//            /// <summary>Retrieves the name of the working directory for a Shell link object</summary>
//            void GetWorkingDirectory([Out(), MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cchMaxPath);
//            /// <summary>Sets the name of the working directory for a Shell link object</summary>
//            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
//            /// <summary>Retrieves the command-line arguments associated with a Shell link object</summary>
//            void GetArguments([Out(), MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cchMaxPath);
//            /// <summary>Sets the command-line arguments for a Shell link object</summary>
//            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
//            /// <summary>Retrieves the hot key for a Shell link object</summary>
//            void GetHotkey(out short pwHotkey);
//            /// <summary>Sets a hot key for a Shell link object</summary>
//            void SetHotkey(short wHotkey);
//            /// <summary>Retrieves the show command for a Shell link object</summary>
//            void GetShowCmd(out int piShowCmd);
//            /// <summary>Sets the show command for a Shell link object. The show command sets the initial show state of the window.</summary>
//            void SetShowCmd(int iShowCmd);
//            /// <summary>Retrieves the location (path and index) of the icon for a Shell link object</summary>
//            void GetIconLocation([Out(), MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath,
//                int cchIconPath, out int piIcon);
//            /// <summary>Sets the location (path and index) of the icon for a Shell link object</summary>
//            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
//            /// <summary>Sets the relative path to the Shell link object</summary>
//            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, int dwReserved);
//            /// <summary>Attempts to find the target of a Shell link, even if it has been moved or renamed</summary>
//            void Resolve(IntPtr hwnd, int fFlags);
//            /// <summary>Sets the path and file name of a Shell link object</summary>
//            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
//        }

//        // http://msdn.microsoft.com/en-us/library/windows/desktop/ms688695(v=vs.85).aspx
//        // http://pinvoke.net/default.aspx/Interfaces/IPersist.html
//        [ComImport, Guid("0000010c-0000-0000-c000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
//        public interface IPersist
//        {
//            [PreserveSig]
//            void GetClassID(out Guid pClassID);
//        }


//        // http://msdn.microsoft.com/en-us/library/windows/desktop/ms687223(v=vs.85).aspx
//        // http://www.pinvoke.net/default.aspx/Interfaces/IPersistFile.html
//        [ComImport, Guid("0000010b-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
//        public interface IPersistFile : IPersist
//        {
//            new void GetClassID(out Guid pClassID);

//            [PreserveSig]
//            int IsDirty();

//            [PreserveSig]
//            void Load([In, MarshalAs(UnmanagedType.LPWStr)] string pszFileName, uint dwMode);

//            [PreserveSig]
//            void Save([In, MarshalAs(UnmanagedType.LPWStr)] string pszFileName,
//                      [In, MarshalAs(UnmanagedType.Bool)] bool fRemember);

//            [PreserveSig]
//            void SaveCompleted([In, MarshalAs(UnmanagedType.LPWStr)] string pszFileName);

//            [PreserveSig]
//            void GetCurFile([In, MarshalAs(UnmanagedType.LPWStr)] string ppszFileName);
//        }
//    }
//}
