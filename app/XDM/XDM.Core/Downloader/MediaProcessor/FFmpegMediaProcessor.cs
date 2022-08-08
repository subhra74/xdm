using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

using XDM.Core;
using System.Diagnostics;
using System.Threading;
using XDM.Core.Util;
using TraceLog;

namespace XDM.Core.MediaProcessor
{
    public class FFmpegMediaProcessor : BaseMediaProcessor
    {
        public override MediaProcessingResult MergeAudioVideStream(string file1, string file2,
            string outfile, CancelFlag cancellationToken, out long outFileSize)
        {
            var args = CreateMergeArgs(file1, file2, outfile);
            var ret = this.ProcessMedia(args, cancellationToken);
            try
            {
                outFileSize = new FileInfo(outfile).Length;
            }
            catch { outFileSize = -1; }
            return ret;
        }

        public override MediaProcessingResult MergeHLSAudioVideStream(string fileList, string outfile,
            CancelFlag cancellationToken, out long outFileSize)
        {
            var args = CreateHLSMergeArgs(fileList, outfile);
            var ret = this.ProcessMedia(args, cancellationToken);
            try
            {
                outFileSize = new FileInfo(outfile).Length;
            }
            catch { outFileSize = -1; }
            return ret;
        }

        public override MediaProcessingResult ConvertToMp3Audio(string infile, string outfile,
            CancelFlag cancellationToken, out long outFileSize)
        {
            var args = CreateMP3MergeArgs(infile, outfile);
            var ret = this.ProcessMedia(args, cancellationToken);
            try
            {
                outFileSize = new FileInfo(outfile).Length;
            }
            catch { outFileSize = -1; }
            return ret;
        }

        private static string[] CreateMergeArgs(string file1, string file2, string outfile)
        {
            var args = new string[] { "-i", file1, "-i", file2, "-acodec", "copy", "-vcodec", "copy",
                "-map", "0", "-map", "1", outfile, "-y" };
            return args;
        }

        private string[] CreateHLSMergeArgs(string file, string outfile)
        {
            var args = new string[] { "-f", "concat", "-safe", "0", "-i", file, "-auto_convert", "1", "-acodec", "copy", "-vcodec", "copy", outfile, "-y" };
            return args;
        }

        private string[] CreateMP3MergeArgs(string file, string outfile)
        {
            var args = new string[] { "-i", file, "-acodec", "libmp3lame", outfile, "-y" };
            return args;
        }

        private MediaProcessingResult ProcessMedia(string[] args, CancelFlag cancellationToken)
        {
            try
            {
                Log.Debug("FFmpeg args: " + string.Join(" ", args));

                var duration = 0L;
                var time = 0L;
                var file = FindFFmpegBinary();
                Log.Debug("FFmpeg binary: " + file);
                var lastTick = Helpers.TickCount();
                var pb = new ProcessStartInfo
                {
                    FileName = file,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

#if NET5_0_OR_GREATER
                foreach (var arg in args)
                {
                    pb.ArgumentList.Add(arg);
                }
#else
                pb.Arguments = XDM.Compatibility.ProcessStartInfoHelper.ArgumentListToArgsString(args);
#endif
                //#if NET5_0_OR_GREATER
                //            foreach (var arg in args)
                //            {
                //                pb.ArgumentList.Add(arg);
                //            }
                //#else
                //            pb.Arguments = CoreFx.Polyfill.ProcessStartInfoHelper.ArgumentListToArgsString(args);
                //#endif
                //var sb = new StringBuilder();
                //foreach (var arg in args)
                //{
                //    sb.Append(arg.Contains(' ') ? "\"" + arg + "\"" : arg);
                //}

                //pb.Arguments = sb.ToString();
                pb.RedirectStandardOutput = true;

                using var proc = Process.Start(pb);
                if (proc == null)
                {
                    throw new Exception("FFmpeg process could not be started - Process.Start");
                }

                proc.OutputDataReceived += (a, b) =>
                {
                    try
                    {
                        var line = b.Data;
                        if (line != null)
                        {
                            Log.Debug(line);
                            if (duration == 0.0)
                            {
                                var md = ParsingHelper.RxDuration.Match(line);
                                var ret = ParsingHelper.ParseTime(md);
                                if (ret > 0) duration = ret;
                            }
                            var mt = ParsingHelper.RxTime.Match(line);
                            var ret2 = ParsingHelper.ParseTime(mt);
                            if (ret2 > 0)
                            {
                                time += ret2;

                                var tick = Helpers.TickCount();

                                if (duration > 0 && tick - lastTick > 1000)
                                {
                                    UpdateProgress((int)((time * 100) / duration));
                                }
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, ex.Message);
                    }
                };

                proc.ErrorDataReceived += (a, b) =>
                {
                    var line = b.Data;
                    if (line != null)
                    {
                        Log.Debug(line);
                    }
                };

                proc.BeginOutputReadLine();

                while (true)
                {
                    if (cancellationToken.IsCancellationRequested)
                    {
                        proc.Kill();
                        break;
                    }
                    if (proc.WaitForExit(100))
                    {
                        proc.WaitForExit(); //see remarks section https://docs.microsoft.com/en-us/dotnet/api/system.diagnostics.process.waitforexit?view=net-6.0
                        break;
                    }
                }

                if (proc.ExitCode == 0)
                {
                    return MediaProcessingResult.Success;
                }
                Log.Debug("FFmpeg exitcode: " + proc.ExitCode);
                return MediaProcessingResult.Failed;
            }
            catch (OperationCanceledException ex)
            {
                Console.WriteLine(ex);
                return MediaProcessingResult.Success;
            }
            catch (FileNotFoundException ex)
            {
                Console.WriteLine(ex);
                return MediaProcessingResult.AppNotFound;
            }
            catch (Exception ex)
            {
                Console.WriteLine(ex);
                return MediaProcessingResult.Failed;
            }
        }

        public static string FindFFmpegBinary()
        {
            var executableNames =
                Environment.OSVersion.Platform == PlatformID.Win32NT ?
                new string[] { "ffmpeg-x86.exe", "ffmpeg.exe" } : new string[] { "ffmpeg" };
            foreach (var executableName in executableNames)
            {
                var path = Path.Combine(Config.DataDir, executableName);
                if (File.Exists(path))
                {
                    return path;
                }
                path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, executableName);
                if (File.Exists(path))
                {
                    return path;
                }
                var ffmpegPathEnvVar = Environment.GetEnvironmentVariable("FFMPEG_HOME");
                //Log.Debug("FFMPEG_HOME: " + ffmpegPathEnvVar);
                if (ffmpegPathEnvVar != null)
                {
                    path = Path.Combine(ffmpegPathEnvVar, executableName);
                    if (File.Exists(path))
                    {
                        return path;
                    }
                }
                path = PlatformHelper.FindExecutableFromSystemPath(executableName);
                if (path != null)
                {
                    return path;
                }
            }
            throw new FileNotFoundException("FFmpeg executable not found");
        }

        public static bool IsFFmpegInstalled()
        {
            try
            {
                FindFFmpegBinary();
            }
            catch (FileNotFoundException)
            {
                return false;
            }
            return true;
        }
    }
}
