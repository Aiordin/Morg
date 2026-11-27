{
  description = "Morg — an edge handle that opens another instance of the foreground app";

  # 用 channels 的 tarball 而不是 github: 前缀：不需要访问 GitHub API，
  # 不会碰到匿名请求的限流（flake.lock 里已固定具体 rev 与 narHash）。
  inputs.nixpkgs.url = "https://channels.nixos.org/nixos-unstable/nixexprs.tar.zst";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];

      pkgsFor =
        system:
        import nixpkgs {
          inherit system;
          config = {
            # Android SDK 与 platform 包在 nixpkgs 中标记为 unfree。
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

      devShellFor =
        system:
        let
          pkgs = pkgsFor system;
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" ];
            buildToolsVersions = [ "35.0.0" ];
            includeNDK = false;
            includeEmulator = false;
            includeSystemImages = false;
          };
          sdk = "${android.androidsdk}/libexec/android-sdk";
        in
        pkgs.mkShell {
          packages = [
            pkgs.jdk17
            pkgs.git
          ];

          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          JAVA_HOME = "${pkgs.jdk17.home}";

          shellHook = ''
            echo "Morg development shell"
            echo "  JDK 17  : $JAVA_HOME"
            echo "  Android : $ANDROID_HOME"
            echo "  build   : ./gradlew :app:assembleDebug"
            echo "  test    : ./gradlew :app:testDebugUnitTest"
          '';
        };
    in
    {
      devShells = nixpkgs.lib.genAttrs systems (system: {
        default = devShellFor system;
      });

      formatter = nixpkgs.lib.genAttrs systems (system: (pkgsFor system).nixfmt);
    };
}
