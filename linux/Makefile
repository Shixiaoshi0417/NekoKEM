CC = /usr/bin/gcc
AR = /usr/bin/ar
CPPFLAGS = -D_POSIX_C_SOURCE=200809L -D_FORTIFY_SOURCE=3 \
	-I../core/include -I../core/src
CFLAGS = -std=c17 -O2 -Wall -Wextra -Wpedantic -Wconversion -Wshadow \
	-Wformat=2 -Wstrict-prototypes -Werror -fstack-protector-strong -fPIE
LDFLAGS = -pie
LDLIBS = -lcrypto
AFL_CC ?= afl-clang-fast
FUZZ_CPPFLAGS = -D_POSIX_C_SOURCE=200809L -D_FORTIFY_SOURCE=3
FUZZ_CFLAGS = -std=c17 -O1 -g -Wall -Wextra -Wpedantic -Wconversion \
	-Wshadow -Wformat=2 -Wstrict-prototypes -Werror \
	-fno-omit-frame-pointer -fsanitize=undefined \
	-fno-sanitize-recover=undefined
FUZZ_LDFLAGS = -fsanitize=undefined

TARGET = nekokem
CORE_LIBRARY = libnekokem_core.a
CORE_SOURCES = ../core/src/nekokem.c \
	../core/src/key_management.c \
	../core/src/nekokem_v3.c ../core/src/kem.c ../core/src/hybrid.c \
	../core/src/aes.c ../core/src/file.c ../core/src/file_v3.c ../core/src/secure_mem.c ../core/src/private_key.c
CLI_SOURCES = src/main.c src/cli.c
SOURCES = $(CLI_SOURCES) $(CORE_SOURCES)
CORE_OBJECTS = $(CORE_SOURCES:.c=.o)
CLI_OBJECTS = $(CLI_SOURCES:.c=.o)
OBJECTS = $(CLI_OBJECTS) $(CORE_OBJECTS)
DEPS = $(OBJECTS:.o=.d)

.PHONY: all core clean test analyze test-ubsan test-parser fuzz-seeds \
	fuzz-build test-key-management test-progress test-progress-large \
	test-file-security

all: $(TARGET)

core: $(CORE_LIBRARY)

$(CORE_LIBRARY): $(CORE_OBJECTS)
	$(AR) rcs $@ $(CORE_OBJECTS)

$(TARGET): $(CLI_OBJECTS) $(CORE_LIBRARY)
	$(CC) $(CFLAGS) $(LDFLAGS) $(CLI_OBJECTS) $(CORE_LIBRARY) \
		$(LDLIBS) -o $@

%.o: %.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -MMD -MP -c $< -o $@

analyze:
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 -fanalyzer -fsyntax-only $(SOURCES)
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 -fanalyzer -fsyntax-only \
		../core/tests/parser_tests.c ../core/src/file.c ../core/src/file_v3.c ../core/src/private_key.c \
		../core/src/secure_mem.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 -fanalyzer -fsyntax-only \
		-DNEKOKEM_TEST_FAULT_INJECTION ../core/tests/file_security_tests.c \
		../core/src/file.c ../core/src/file_v3.c ../core/src/secure_mem.c

test-ubsan:
	@set -eu; \
	ubsan_binary=$$(mktemp /tmp/nekokem-ubsan.XXXXXX); \
	cleanup_ubsan() { rm -f "$$ubsan_binary"; }; \
	trap cleanup_ubsan EXIT INT TERM; \
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 \
		-fsanitize=undefined -fno-sanitize-recover=undefined \
		$(LDFLAGS) $(SOURCES) $(LDLIBS) -fsanitize=undefined \
		-o "$$ubsan_binary"; \
	UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
		sh ../core/tests/security_paths.sh "$$ubsan_binary"

test-parser:
	@set -eu; \
	parser_binary=$$(mktemp /tmp/nekokem-parser-tests.XXXXXX); \
	cleanup_parser() { rm -f "$$parser_binary"; }; \
	trap cleanup_parser EXIT INT TERM; \
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 \
		-fsanitize=undefined -fno-sanitize-recover=undefined \
		$(LDFLAGS) ../core/tests/parser_tests.c ../core/src/file.c ../core/src/file_v3.c \
		../core/src/private_key.c ../core/src/secure_mem.c $(LDLIBS) \
		-fsanitize=undefined -o "$$parser_binary"; \
	UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
		"$$parser_binary"

test-file-security:
	@set -eu; \
	security_binary=$$(mktemp /tmp/nekokem-file-security-tests.XXXXXX); \
	cleanup_security() { rm -f "$$security_binary"; }; \
	trap cleanup_security EXIT INT TERM; \
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 \
		-DNEKOKEM_TEST_FAULT_INJECTION \
		-fsanitize=undefined -fno-sanitize-recover=undefined \
		$(LDFLAGS) ../core/tests/file_security_tests.c ../core/src/file.c ../core/src/file_v3.c \
		../core/src/secure_mem.c $(LDLIBS) -fsanitize=undefined \
		-o "$$security_binary"; \
	UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
		"$$security_binary"

fuzz-seeds:
	sh ../core/fuzz/generate_seeds.sh

fuzz-build: fuzz-seeds
	@command -v "$(AFL_CC)" >/dev/null || { \
		echo "afl-clang-fast is required (install afl++)" >&2; \
		exit 1; \
	}
	mkdir -p ../core/fuzz/bin
	$(AFL_CC) $(FUZZ_CPPFLAGS) $(FUZZ_CFLAGS) \
		../core/fuzz/fuzz_nkem.c ../core/src/file.c ../core/src/file_v3.c ../core/src/secure_mem.c \
		$(FUZZ_LDFLAGS) $(LDLIBS) -o ../core/fuzz/bin/fuzz_nkem
	$(AFL_CC) $(FUZZ_CPPFLAGS) $(FUZZ_CFLAGS) \
		../core/fuzz/fuzz_nkpr.c ../core/src/private_key.c ../core/src/file.c ../core/src/file_v3.c \
		../core/src/secure_mem.c $(FUZZ_LDFLAGS) $(LDLIBS) \
		-o ../core/fuzz/bin/fuzz_nkpr


test-key-management: $(CORE_LIBRARY)
	@set -eu; \
	key_binary=$$(mktemp /tmp/nekokem-key-management-tests.XXXXXX); \
	cleanup_key() { rm -f "$$key_binary"; }; \
	trap cleanup_key EXIT INT TERM; \
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) \
		../core/tests/key_management_tests.c $(CORE_LIBRARY) $(LDLIBS) \
		-o "$$key_binary"; \
	"$$key_binary"

test-progress test-progress-large: $(CORE_LIBRARY)
	@set -eu; \
	progress_binary=$$(mktemp /tmp/nekokem-progress-tests.XXXXXX); \
	cleanup_progress() { rm -f "$$progress_binary"; }; \
	trap cleanup_progress EXIT INT TERM; \
	$(CC) $(CPPFLAGS) $(CFLAGS) $(LDFLAGS) \
		../core/tests/progress_tests.c $(CORE_LIBRARY) $(LDLIBS) \
		-o "$$progress_binary"; \
	if [ "$@" = "test-progress-large" ]; then \
		NEKOKEM_PROGRESS_TEST_BYTES=3221225472 \
			"$$progress_binary"; \
	else \
		"$$progress_binary"; \
	fi

test: $(TARGET) analyze test-ubsan test-parser test-file-security \
	test-key-management test-progress
	@set -eu; \
	test_dir=$$(mktemp -d); \
	cleanup() { \
		rm -f "$$test_dir/nekokem" "$$test_dir/test.txt" \
			"$$test_dir/v1-output.txt" "$$test_dir/hybrid-output.txt" \
			"$$test_dir/tampered-output.txt" \
			"$$test_dir/wrong-password-output.txt" \
			"$$test_dir/tampered-key-output.txt" \
			"$$test_dir/wrong-output.txt" \
			"$$test_dir/correct-private.key.enc" \
			"$$test_dir/tampered-private.key.enc" \
			"$$test_dir/interactive.txt" \
			"$$test_dir/interactive-original.txt" \
			"$$test_dir/plaintext/interactive.txt" \
			"$$test_dir/menu-output.txt" \
			"$$test_dir/fingerprint-output.txt" \
			"$$test_dir/private-echo-check.log" \
			"$$test_dir/password-echo-check.log" \
			"$$test_dir/long-input-output.txt" \
			"$$test_dir/long-input-error.txt" \
			"$$test_dir/long-paste-output.txt" \
			"$$test_dir/long-paste-error.txt" \
			"$$test_dir/encrypted/hybrid.nkem" \
			"$$test_dir/encrypted/tampered.nkem" \
			"$$test_dir/encrypted/interactive.txt.nkem" \
			"$$test_dir/keys/public.key" \
			"$$test_dir/keys/private.key" \
			"$$test_dir/keys/private.key.enc"; \
		rmdir "$$test_dir/plaintext" "$$test_dir/encrypted" \
			"$$test_dir/keys" "$$test_dir" 2>/dev/null || true; \
	}; \
	trap cleanup EXIT INT TERM; \
	mkdir "$$test_dir/encrypted"; \
	install -m 0755 "$(CURDIR)/$(TARGET)" "$$test_dir/nekokem"; \
	printf 'NekoKEM C17 end-to-end test\nBinary:\000\001\377\n' > "$$test_dir/test.txt"; \
	cd "$$test_dir"; \
	key_password='test-only-hybrid-password'; \
	interactive_password='test-only-interactive-password'; \
	printf '%s\n%s\n' "$$key_password" "$$key_password" | \
		./nekokem keygen; \
	test -s keys/public.key; \
	test -s keys/private.key.enc; \
	test ! -e keys/private.key; \
	test "$$(stat -c %a keys/private.key.enc)" = 600; \
	test "$$(dd if=keys/private.key.enc bs=1 count=4 \
		status=none)" = NKPR; \
	if grep -a -q -- '-----BEGIN PRIVATE KEY-----' \
		keys/private.key.enc; then \
		echo "Protected private key contains plaintext PEM" >&2; \
		exit 1; \
	fi; \
	./nekokem encrypt hybrid test.txt encrypted/hybrid.nkem keys/public.key; \
	printf '%s\n' "$$key_password" | \
		./nekokem decrypt hybrid encrypted/hybrid.nkem \
			hybrid-output.txt keys/private.key.enc; \
	sha256sum test.txt hybrid-output.txt; \
	cmp test.txt hybrid-output.txt; \
	if printf 'wrong-password\n' | \
		./nekokem decrypt hybrid encrypted/hybrid.nkem \
			wrong-password-output.txt keys/private.key.enc; then \
		echo "Wrong private-key password was accepted" >&2; \
		exit 1; \
	fi; \
	test ! -e wrong-password-output.txt; \
	cp encrypted/hybrid.nkem encrypted/tampered.nkem; \
	printf '\000' | dd of=encrypted/tampered.nkem bs=1 seek=1664 count=1 conv=notrunc 2>/dev/null; \
	if cmp -s encrypted/hybrid.nkem encrypted/tampered.nkem; then \
		printf '\377' | dd of=encrypted/tampered.nkem bs=1 seek=1664 count=1 conv=notrunc 2>/dev/null; \
	fi; \
	if printf '%s\n' "$$key_password" | \
		./nekokem decrypt hybrid encrypted/tampered.nkem \
			tampered-output.txt keys/private.key.enc; then \
		echo "Tampered hybrid ciphertext was accepted" >&2; \
		exit 1; \
	fi; \
	test ! -e tampered-output.txt; \
	cp keys/private.key.enc tampered-private.key.enc; \
	private_size=$$(wc -c < tampered-private.key.enc); \
	private_offset=$$((private_size - 1)); \
	printf '\001' | dd of=tampered-private.key.enc bs=1 \
		seek="$$private_offset" count=1 conv=notrunc 2>/dev/null; \
	if cmp -s keys/private.key.enc tampered-private.key.enc; then \
		printf '\377' | dd of=tampered-private.key.enc bs=1 \
			seek="$$private_offset" count=1 conv=notrunc \
			2>/dev/null; \
	fi; \
	if printf '%s\n' "$$key_password" | \
		./nekokem decrypt hybrid encrypted/hybrid.nkem \
			tampered-key-output.txt tampered-private.key.enc; then \
		echo "Tampered protected private key was accepted" >&2; \
		exit 1; \
	fi; \
	test ! -e tampered-key-output.txt; \
	mv keys/private.key.enc correct-private.key.enc; \
	printf '%s\n%s\n' "$$key_password" "$$key_password" | \
		./nekokem keygen hybrid; \
	if printf '%s\n' "$$key_password" | \
		./nekokem decrypt hybrid encrypted/hybrid.nkem \
			wrong-output.txt keys/private.key.enc; then \
		echo "Wrong hybrid private key was accepted" >&2; \
		exit 1; \
	fi; \
	test ! -e wrong-output.txt; \
	rm -f encrypted/hybrid.nkem \
		encrypted/tampered.nkem; \
	rmdir encrypted; \
	test ! -e encrypted; \
	printf '5\n' | ./nekokem > menu-output.txt; \
	grep -Fx '      NekoKEM' menu-output.txt >/dev/null; \
	grep -Fx '1. 生成密钥' menu-output.txt >/dev/null; \
	grep -Fx '5. 退出' menu-output.txt >/dev/null; \
	awk 'BEGIN { for (i = 0; i < 5000; ++i) printf "A"; \
		printf "\\n5\\n"; }' | ./nekokem >long-input-output.txt \
		2>long-input-error.txt; \
	grep -F 'Input exceeds 4096 bytes and was discarded' \
		long-input-error.txt >/dev/null; \
	{ printf '2\n2\n'; \
		awk 'BEGIN { for (i = 0; i < 17000; ++i) printf "A"; \
			printf "\\n"; }'; \
		printf '5\n'; \
	} | ./nekokem >long-paste-output.txt 2>long-paste-error.txt; \
	grep -F 'Pasted-key line exceeds 16384 bytes and was discarded' \
		long-paste-error.txt >/dev/null; \
	test -z "$$(find /tmp -maxdepth 1 -type d \
		-name 'nekokem-paste.*' -print -quit)"; \
	printf '1\n%s\n%s\n5\n' "$$interactive_password" \
		"$$interactive_password" | ./nekokem >/dev/null; \
	test -s keys/public.key; \
	test -s keys/private.key.enc; \
	test ! -e keys/private.key; \
	test "$$(stat -c %a keys/private.key.enc)" = 600; \
	printf '4\n1\nkeys/public.key\n5\n' | ./nekokem \
		> fingerprint-output.txt; \
	grep -Eq '([0-9A-F]{2}:){31}[0-9A-F]{2}' \
		fingerprint-output.txt; \
	mkdir plaintext; \
	cp test.txt plaintext/interactive.txt; \
	{ printf '2\n2\n'; cat keys/public.key; \
		printf '%s\n5\n' "$$test_dir/plaintext/interactive.txt"; \
	} | ./nekokem >/dev/null; \
	test -d encrypted; \
	test "$$(stat -c %a encrypted)" = 700; \
	test -f encrypted/interactive.txt.nkem; \
	test ! -e plaintext/interactive.txt.nkem; \
	mv plaintext/interactive.txt interactive-original.txt; \
	rmdir plaintext; \
	test ! -e plaintext; \
	printf '3\n1\nkeys/private.key.enc\nencrypted/interactive.txt.nkem\n%s\n5\n' \
		"$$interactive_password" | ./nekokem >/dev/null; \
	test -d plaintext; \
	test "$$(stat -c %a plaintext)" = 700; \
	test -f plaintext/interactive.txt; \
	test ! -e interactive.txt; \
	sha256sum interactive-original.txt plaintext/interactive.txt; \
	cmp interactive-original.txt plaintext/interactive.txt; \
	umask 077; \
	{ printf '3\n2\n'; sleep 1; \
		printf 'PRIVATE-ECHO-SENTINEL\n-----END PRIVATE KEY-----\n-----END PRIVATE KEY-----\n'; \
	} | /usr/bin/script -qec './nekokem' private-echo-check.log \
		>/dev/null 2>&1; \
	if grep -q 'PRIVATE-ECHO-SENTINEL' private-echo-check.log; then \
		echo "Pasted private-key input was echoed" >&2; \
		exit 1; \
	fi; \
	test "$$(stat -c %a private-echo-check.log)" = 600; \
	{ sleep 1; printf 'PASSWORD-ECHO-SENTINEL\n'; \
		sleep 1; printf 'PASSWORD-ECHO-SENTINEL\n'; \
	} | /usr/bin/script -qec './nekokem keygen hybrid' \
		password-echo-check.log >/dev/null 2>&1; \
	if grep -q 'PASSWORD-ECHO-SENTINEL' \
		password-echo-check.log; then \
		echo "Private-key password was echoed" >&2; \
		exit 1; \
	fi; \
	test "$$(stat -c %a password-echo-check.log)" = 600; \
	test -z "$$(find . -type f -name '*.tmp.*' -print -quit)"; \
	test -z "$$(find . -type f -name 'private.key' -print -quit)"; \
	echo "NekoKEM v3, NKPR, and interactive CLI tests passed"

clean:
	rm -f $(TARGET) $(CORE_LIBRARY) $(OBJECTS) $(DEPS)
	rm -f ../core/fuzz/bin/fuzz_nkem ../core/fuzz/bin/fuzz_nkpr

-include $(DEPS)
