# Nix / NixOS packaging

The repository root contains a Nix flake that packages XDM for NixOS and provides a
development shell with the JDK 25/Maven toolchain already configured.

## Installation on NixOS

Add the repository as a flake input:

```nix
# flake.nix
{
  inputs.xdm = {
    url = "github:subhra74/xdm";
    inputs.nixpkgs.follows = "nixpkgs";
  };
}
```

Then install it system-wide with `environment.systemPackages`, or per-user with
home-manager's `home.packages`:

```nix
# configuration.nix
{ inputs, pkgs, ... }:
{
  environment.systemPackages = [
    inputs.xdm.packages.${pkgs.stdenv.hostPlatform.system}.default
  ];
}
```

`{ inputs, ... }` only works when the flake passes its inputs to the module. For a
flake-based NixOS configuration:

```nix
# flake.nix
nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
  specialArgs = { inherit inputs; };
  modules = [ ./configuration.nix ];
};
```

With home-manager, use `extraSpecialArgs = { inherit inputs; };` instead.

## Run without installing

You can also run XDM on NixOS, or any system with Nix, without installing it:

```sh
nix run github:subhra74/xdm
```

Or enter a shell where `xdm-app` is available as a command:

```sh
nix shell github:subhra74/xdm
```

## Browser integration

The installed desktop entry registers the `xdm-app://` scheme. Set it as the default
handler so links from the browser extension reach XDM:

```nix
xdg.mimeApps = {
  enable = true;
  defaultApplications."x-scheme-handler/xdm-app" = "xdm-app.desktop";
};
```

## Build the package

```sh
nix build .#default
./result/bin/xdm-app
```

## Development shell

The flake also provides a development shell with the build tools and toolchain
configured.

- Clone the repository and enter the cloned directory:

  ```sh
  git clone https://github.com/subhra74/xdm
  cd xdm
  ```

- Run `nix develop .` to enter the default development shell.

The shell provides JDK 25 and Maven, and sets `MAVEN_ARGS` to a generated
`toolchains.xml`. The normal Maven commands therefore work without writing
`~/.m2/toolchains.xml`:

```sh
mvn -q -pl xdm-app -am package
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest
```

Install ffmpeg (or uncomment `ffmpeg-headless` in `flake.nix`) if you want the
streaming tests to run; they skip themselves when ffmpeg/ffprobe are not on `PATH`.
