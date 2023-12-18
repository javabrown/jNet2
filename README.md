# jNet2
Low level app to identify ethernet level packets


##Make sure you have NASM (Netwide Assembler) installed on your system. You can install it using Homebrew:
``brew install nasm``


## 
nasm -f macho64 hello.asm -o hello.o
gcc hello.o -o hello
ld -macosx_version_min 10.8.0 -lSystem hello.o -o hello
./hello

