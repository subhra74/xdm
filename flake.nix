{
  description = "Xtreme Download Manager (XDM) - Nix package";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];

      mkXdm =
        pkgs:
        let
          lib = pkgs.lib;
          version = (lib.importJSON ./xdm-app/src/main/resources/app-version.json).currentVersion;
          # The JDK modules XDM needs. sourced from  packaging/build-bundle.sh
          jre = pkgs.jre25_minimal.override {
            modules = [
              "java.desktop"
              "java.logging"
              "jdk.crypto.ec"
              "jdk.unsupported"
            ];
          };

          # Runtime libraries the linked AWT/X11
          runtimeLibs = with pkgs; [
            libx11
            libxext
            libxrender
            libxtst
            libxi
            freetype
            fontconfig
            alsa-lib
            wayland
            libxkbcommon
            gtk3
          ];
        in
        pkgs.maven.buildMavenPackage {
          pname = "xdm";
          inherit version;
          src = self;
          # xdm-app compiles at Java 25 and the root POM selects that compiler through
          # maven-toolchains-plugin. Run Maven on JDK 25 and give the plugin a toolchain
          # entry pointing at the same JDK.
          mvnJdk = pkgs.jdk25;
          mvnParameters = "-t toolchains.xml";

          postPatch = ''
            cat > toolchains.xml <<'EOF'
            <toolchains>
              <toolchain>
                <type>jdk</type>
                <provides><version>25</version></provides>
                <configuration><jdkHome>${pkgs.jdk25.home}</jdkHome></configuration>
              </toolchain>
            </toolchains>
            EOF
          '';

          # The tests spin up real networked downloads; the package only needs the jar.
          doCheck = false;

          # Hash of the Maven dependency closure (Kotlin compiler, Maven plugins, test deps).
          # When the POMs or dependency versions change, set this back to lib.fakeHash, run
          # `nix build .#default`, and copy the hash Nix reports into here.
          mvnHash = "sha256-o0854Rb33CtGnkn0V9qDxVIgHY+47WfpxSQKOc08K8o=";

          nativeBuildInputs = [
            pkgs.copyDesktopItems
            pkgs.jdk25
          ];

          desktopItems = [
            (pkgs.makeDesktopItem {
              name = "xdm-app";
              desktopName = "Xtreme Download Manager";
              genericName = "Download Manager";
              comment = "Download accelerator and video downloader";
              exec = "xdm-app %U";
              icon = "xdm-app";
              terminal = false;
              categories = [
                "Network"
                "FileTransfer"
              ];
              keywords = [
                "download"
                "accelerator"
                "video"
                "hls"
                "dash"
              ];
              mimeTypes = [ "x-scheme-handler/xdm-app" ];
              startupNotify = false;
              startupWMClass = "xdm-app-AppMain";
            })
          ];

          installPhase = ''
            runHook preInstall

            install -Dm644 xdm-app/target/xdm-app.jar \
              $out/share/xdm-app/xdm-app.jar

            for size in 16 24 32 48 64 128 256 512; do
              install -Dm644 xdm-app/src/main/resources/images/xdm-logo-$size.png \
                $out/share/icons/hicolor/''${size}x''${size}/apps/xdm-app.png
            done

            mkdir -p $out/bin
            # -Djpackage.app-path makes the app treat this wrapper as its installed launcher.
            # The login entry it writes then uses the stable profile path ($0) instead of a
            # hard-coded store path that would break on upgrade or garbage collection.
            #
            # ffmpeg is optional: XDM 9 has its own native HLS/DASH transmuxer. To also use
            # the legacy FFmpeg muxer without a system ffmpeg, add ffmpeg to the wrapper's
            # PATH here (pkgs.lib.makeBinPath [ pkgs.ffmpeg ]).
            cat > $out/bin/xdm-app <<'EOF'
            #!${pkgs.runtimeShell}
            export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath runtimeLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
            exec ${jre}/bin/java \
              --enable-native-access=ALL-UNNAMED \
              "-Djpackage.app-path=$0" \
              -jar ${placeholder "out"}/share/xdm-app/xdm-app.jar "$@"
            EOF
            chmod +x $out/bin/xdm-app

            runHook postInstall
          '';

          meta = {
            description = "Download accelerator and video downloader (XDM)";
            homepage = "https://github.com/subhra74/xdm";
            license = lib.licenses.gpl3Only;
            mainProgram = "xdm-app";
            platforms = [
              "x86_64-linux"
              "aarch64-linux"
            ];
            sourceProvenance = with lib.sourceTypes; [
              fromSource
              binaryBytecode
            ];
          };
        };
    in
    {
      packages = nixpkgs.lib.genAttrs systems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          xdm = mkXdm pkgs;
        in
        {
          default = xdm;
          xdm = xdm;
        }
      );

      devShells = nixpkgs.lib.genAttrs systems (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
          toolchains = pkgs.writeText "toolchains.xml" ''
            <toolchains>
              <toolchain>
                <type>jdk</type>
                <provides><version>25</version></provides>
                <configuration><jdkHome>${pkgs.jdk25.home}</jdkHome></configuration>
              </toolchain>
            </toolchains>
          '';
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk25
              maven
              git
              # The streaming tests use ffmpeg/ffprobe when present and skip otherwise.
              # ffmpeg-headless
            ];
            JAVA_HOME = pkgs.jdk25.home;
            shellHook = ''
              export MAVEN_ARGS="-t ${toolchains}"
            '';
          };
        }
      );
    };
}
