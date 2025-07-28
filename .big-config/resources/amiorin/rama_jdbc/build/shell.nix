{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = with pkgs; [
    python3
    postgresql_16
    just
    sql-migrate
  ];
}
