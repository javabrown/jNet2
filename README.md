# jNet2
Low level app to identify ethernet level packets


##Make sure you have NASM (Netwide Assembler) installed on your system. You can install it using Homebrew:
``brew install nasm``


## 
nasm -f macho64 hello.asm -o hello.o
gcc hello.o -o hello
ld -macosx_version_min 10.8.0 -lSystem hello.o -o hello
./hello

========
## JAIN SIP App

A minimal Java implementation of a JAIN SIP server that listens for incoming SIP traffic and handles basic call flows using the NIST reference implementation.

### Prerequisites
* Java 11 or higher
* Maven 3.x
* Homebrew (for macOS users to install SIPp)

---

### Step 1: Install SIPp (Test Utility)
Open your terminal and fix Homebrew permissions to install the SIPp test client:

```bash
# Fix Homebrew directory ownership
sudo chown -R \$(whoami) /opt/homebrew

# Install SIPp
brew cleanup && brew install sipp

# Verify installation
sipp -v
```

---

### Step 2: Compile and Run the Java App
Navigate to the root directory of this project (where `pom.xml` is located) and execute these commands:

```bash
# Clean and compile the project
mvn clean compile

# Start the JAIN SIP server listener
mvn exec:java -Dexec.mainClass="BasicSipClient"
```
*Expected console output:* `SIP Stack running on 127.0.0.1:5060...`

---

### Step 3: Run the Test Simulation
Keep the Java app running in your first terminal window. Open a **new, separate terminal window** and launch a simulated call against your server:

```bash
sipp 127.0.0.1:5060 -m 1 -sn uac
```

### Expected Results
* **Java Terminal:** Logs incoming `INVITE`, sends `200 OK`, receives `ACK`, and handles the final `BYE` hang-up.
* **SIPp Terminal:** Shows a dashboard indicating `Successful call | 1` and exits cleanly without errors.
