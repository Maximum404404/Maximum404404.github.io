using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

// Tiny launcher: runs build-radio.ps1 (which rebuilds radio-tracks.js from the
// Songs / DJI folders). Double-click RefreshRadio.exe after adding/removing tracks.
class RefreshRadio
{
    static void Main()
    {
        string dir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        string script = Path.Combine(dir, "build-radio.ps1");

        Console.Title = "Refresh Radio Playlist";
        Console.WriteLine("Refreshing radio playlist from the Songs and DJI folders...");
        Console.WriteLine();

        if (!File.Exists(script))
        {
            Console.WriteLine("ERROR: build-radio.ps1 was not found next to this app.");
            Console.WriteLine("Keep RefreshRadio.exe in the same folder as build-radio.ps1.");
            Console.WriteLine();
            Console.WriteLine("Press any key to close.");
            Console.ReadKey();
            return;
        }

        try
        {
            var psi = new ProcessStartInfo("powershell.exe",
                "-ExecutionPolicy Bypass -NoProfile -File \"" + script + "\"");
            psi.WorkingDirectory = dir;
            psi.UseShellExecute = false;
            Process p = Process.Start(psi);
            p.WaitForExit();
        }
        catch (Exception ex)
        {
            Console.WriteLine("ERROR: " + ex.Message);
        }

        Console.WriteLine();
        Console.WriteLine("Done. Press any key to close.");
        Console.ReadKey();
    }
}
