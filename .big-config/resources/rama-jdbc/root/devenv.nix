{ pkgs, lib, config, inputs, ... }:

{
  packages = [
    pkgs.git
    pkgs.jet
    pkgs.process-compose
    pkgs.babashka
    pkgs.python3
    pkgs.postgresql_16
    pkgs.just
    pkgs.sql-migrate
  ];
  languages.clojure.enable = true;
}
