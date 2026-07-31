CC = /usr/bin/gcc
CPPFLAGS = -D_POSIX_C_SOURCE=200809L -D_FORTIFY_SOURCE=3
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
SOURCES = src/main.c src/kem.c src/hybrid.c src/aes.c src/file.c \
	src/secure_mem.c src/private_key.c
OBJECTS = $(SOURCES:.c=.o)
DEPS = $(OBJECTS:.o=.d)

.PHONY: all clean test analyze test-ubsan test-parser fuzz-seeds \
	fuzz-build

all: $(TARGET)

$(TARGET): $(OBJECTS)
	$(CC) $(CFLAGS) $(LDFLAGS) $(OBJECTS) $(LDLIBS) -o $@

src/%.o: src/%.c
	$(CC) $(CPPFLAGS) $(CFLAGS) -MMD -MP -c $< -o $@

analyze:
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 -fanalyzer -fsyntax-only $(SOURCES)
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 -fanalyzer -fsyntax-only \
		tests/parser_tests.c src/file.c src/private_key.c \
		src/secure_mem.c

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
		sh tests/security_paths.sh "$$ubsan_binary"

test-parser:
	@set -eu; \
	parser_binary=$$(mktemp /tmp/nekokem-parser-tests.XXXXXX); \
	cleanup_parser() { rm -f "$$parser_binary"; }; \
	trap cleanup_parser EXIT INT TERM; \
	$(CC) $(CPPFLAGS) $(CFLAGS) -O1 \
		-fsanitize=undefined -fno-sanitize-recover=undefined \
		$(LDFLAGS) tests/parser_tests.c src/file.c \
		src/private_key.c src/secure_mem.c $(LDLIBS) \
		-fsanitize=undefined -o "$$parser_binary"; \
	UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
		"$$parser_binary"

fuzz-seeds:
	sh fuzz/generate_seeds.sh

fuzz-build: fuzz-seeds
	@command -v "$(AFL_CC)" >/dev/null || { \
		echo "afl-clang-fast is required (install afl++)" >&2; \
		exit 1; \
	}
	mkdir -p fuzz/bin
	$(AFL_CC) $(FUZZ_CPPFLAGS) $(FUZZ_CFLAGS) \
		fuzz/fuzz_nkem.c src/file.c src/secure_mem.c \
		$(FUZZ_LDFLAGS) $(LDLIBS) -o fuzz/bin/fuzz_nkem
	$(AFL_CC) $(FUZZ_CPPFLAGS) $(FUZZ_CFLAGS) \
		fuzz/fuzz_nkpr.c src/private_key.c src/file.c \
		src/secure_mem.c $(FUZZ_LDFLAGS) $(LDLIBS) \
		-o fuzz/bin/fuzz_nkpr

test: $(TARGET) analyze test-ubsan test-parser
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
			"$$test_dir/encrypted/v1.nkem" \
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
	./nekokem keygen; \
	./nekokem encrypt test.txt encrypted/v1.nkem keys/public.key; \
	./nekokem decrypt encrypted/v1.nkem v1-output.txt keys/private.key; \
	sha256sum test.txt v1-output.txt; \
	cmp test.txt v1-output.txt; \
	rm -f keys/private.key; \
	printf '%s\n%s\n' "$$key_password" "$$key_password" | \
		./nekokem keygen hybrid; \
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
	rm -f encrypted/v1.nkem encrypted/hybrid.nkem \
		encrypted/tampered.nkem; \
	rmdir encrypted; \
	test ! -e encrypted; \
	printf '5\n' | ./nekokem > menu-output.txt; \
	grep -Fx '      NekoKEM' menu-output.txt >/dev/null; \
	grep -Fx '1. 生成密钥' menu-output.txt >/dev/null; \
	grep -Fx '5. 退出' menu-output.txt >/dev/null; \
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
	echo "NekoKEM v1, v2 hybrid, NKPR, and interactive CLI tests passed"

clean:
	rm -f $(TARGET) $(OBJECTS) $(DEPS)
	rm -f fuzz/bin/fuzz_nkem fuzz/bin/fuzz_nkpr

-include $(DEPS)
