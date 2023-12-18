section .data
    buffer resb 1024    ; Buffer to store received data

section .text
    global _start

_start:
    ; Create socket
    mov rax, 41         ; Socket syscall number
    mov rdi, 2          ; AF_INET (IPv4)
    mov rsi, 1          ; SOCK_STREAM (TCP)
    mov rdx, 0          ; Protocol (0 for default)
    syscall

    ; Bind socket to localhost:8080
    mov rax, 49         ; Bind syscall number
    mov rdi, 0          ; Socket file descriptor
    lea rsi, [rip + sockaddr]  ; Pointer to sockaddr structure
    mov rdx, 16         ; Length of sockaddr structure
    syscall

    ; Listen for incoming connections
    mov rax, 50         ; Listen syscall number
    mov rdi, 0          ; Socket file descriptor
    mov rsi, 5          ; Backlog (number of pending connections)
    syscall

    ; Accept incoming connection
    mov rax, 43         ; Accept syscall number
    mov rdi, 0          ; Socket file descriptor
    lea rsi, [rip + sockaddr]  ; Pointer to sockaddr structure
    lea rdx, [rip + addrlen]   ; Pointer to addrlen
    syscall

    ; Receive data from the client
    mov rax, 0          ; Read syscall number
    mov rdi, [rip + connfd]    ; Connection file descriptor
    lea rsi, [rip + buffer]    ; Pointer to buffer
    mov rdx, 1024       ; Maximum number of bytes to read
    syscall

    ; Log the received data (replace this with your logging code)
    mov rdi, buffer
    call log_data

    ; Close the connection
    mov rax, 3          ; Close syscall number
    mov rdi, [rip + connfd]    ; Connection file descriptor
    syscall

    ; Exit the program
    mov rax, 60         ; Exit syscall number
    xor rdi, rdi        ; Exit code 0
    syscall

section .data
    sockaddr db 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0    ; sockaddr structure for localhost:8080
    addrlen dq 16       ; Address length
    connfd  dq 0        ; Connection file descriptor

section .text
log_data:
    ; Your logging code goes here
    ; The address of the data is in rdi
    ; You may use syscalls to write to a file or perform other logging operations
    ret
