# just is a handy way to save and run project-specific commands.
#
# https://github.com/casey/just

# list all tasks
default:
  just --list

# Cleans any result produced by Nix or associated tools
clean:
  rm -rf result*
alias c := clean

idea:
  nohup idea-ultimate . > /dev/null 2>&1 &
