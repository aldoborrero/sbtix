{
  inputs,
  pkgs,
}: let
  inherit
    (pkgs)
    lib
    stdenv
    system
    ;

  # devshell
  devshell = import inputs.devshell {inherit system;};

  # devshell utilities
  pkgWithCategory = category: package: {inherit package category;};

  # devshell categories
  util = pkgWithCategory "utils";
in {
  default = devshell.mkShell {
    name = "sbtix";
    packages = with pkgs; [
      just # https://github.com/casey/just
    ];
    commands = [
      (util pkgs.just)
    ];
  };
}
