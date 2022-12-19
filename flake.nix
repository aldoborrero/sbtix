{
  description = "Sbtix";

  inputs = {
    # packages
    nixpkgs.url = github:NixOS/nixpkgs/nixpkgs-unstable;

    # utils
    fu.url = github:Numtide/flake-utils;
    treefmt-nix.url = github:numtide/treefmt-nix;
    devshell = {
      url = github:numtide/devshell;
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "fu";
    };
  };

  outputs = {
    self,
    fu,
    nixpkgs,
    treefmt-nix,
    ...
  } @ inputs: let
    inherit (fu.lib) eachSystem system mkApp;

    supportedSystems = with system; [x86_64-linux];
  in (eachSystem supportedSystems (system: let
    pkgs = import nixpkgs {
      inherit system;
      config.allowUnfree = true;
    };

    treefmt = treefmt-nix.lib.mkWrapper pkgs {
      projectRootFile = ".git/config";
      programs = {
        alejandra.enable = true;
        prettier.enable = true;
      };
    };

    sbtix = pkgs.callPackage ./sbtix-tool.nix {};
  in {
    # nix build .#<app>
    packages.default = sbtix;

    # nix run .#sbtix
    apps.default = mkApp {
      name = "sbtix";
      drv = sbtix;
    };

    # generic shell:  nix develop
    # specific shell: nix develop .#<devShells.${system}.default>
    devShells = import ./devshell.nix {inherit inputs pkgs;};

    # nix flake check
    checks = {inherit treefmt;};
  }));
}
