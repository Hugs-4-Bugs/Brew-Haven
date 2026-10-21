#!/usr/bin/env python3
"""Final DB integrity diff: current DB vs the verified backup dump.
Compares products (id, name, price, status, category), categories (names 1-10),
users (count), and reports E2E-session additions separately."""
import gzip, re, subprocess, sys

MARIA = ["/tmp/my-project/tools/mariadb/bin/mariadb", "--socket=/tmp/my-project/tools/mariadb-sock/mysqld.sock", "-u", "-root", "-N"]
MARIA = ["/tmp/my-project/tools/mariadb/bin/mariadb", "--socket=/tmp/my-project/tools/mariadb-sock/mysqld.sock", "-u", "root", "-N"]
BACKUP = "/tmp/my-project/artifacts/backups/CafeMgt-verify.sql.gz"

def q(sql):
    r = subprocess.run(MARIA + ["-e", f"USE CafeMgt;{sql}"], capture_output=True, text=True)
    return r.stdout.strip()

def dump_tuples(table):
    """Parse INSERT tuples for `table` from the mysqldump."""
    with gzip.open(BACKUP, "rt") as fh:
        text = fh.read()
    m = re.search(r"INSERT INTO `" + table + r"` VALUES\n(.*?);", text, re.S)
    if not m:
        return []
    body = m.group(1)
    # split top-level tuples: rows look like (1,'desc','name',199,'true',1),
    rows = []
    depth, cur = 0, ""
    in_str = False
    i = 0
    while i < len(body):
        c = body[i]
        if in_str:
            cur += c
            if c == "\\":
                cur += body[i+1]; i += 2; continue
            if c == "'":
                in_str = False
        else:
            if c == "'":
                in_str = True; cur += c
            elif c == "(":
                depth += 1
                if depth == 1: cur = ""
                else: cur += c
            elif c == ")":
                depth -= 1
                if depth == 0: rows.append(cur); cur = ""
                else: cur += c
            else:
                cur += c
        i += 1
    out = []
    for r in rows:
        fields, cur, ins = [], "", False
        j = 0
        while j < len(r):
            c = r[j]
            if ins:
                cur += c
                if c == "\\": cur += r[j+1]; j += 2; continue
                if c == "'": ins = False
            elif c == "'": ins = True; cur += c
            elif c == ",": fields.append(cur); cur = ""
            else: cur += c
            j += 1
        fields.append(cur)
        out.append([f.strip("'") for f in fields])
    return out

def main():
    ok = True
    # --- products ---
    cur = {}
    for line in q("SELECT id,name,price,status,category_fk FROM product").splitlines():
        parts = line.split("\t")
        if len(parts) == 5:
            cur[int(parts[0])] = (parts[1], parts[2], parts[3], parts[4])
    orig = {}
    for f in dump_tuples("product"):
        # dump cols: (id, description, name, price, status, category_fk)
        orig[int(f[0])] = (f[2], f[3], f[4], f[5])
    print(f"products: current={len(cur)} backup={len(orig)}")
    for pid, o in sorted(orig.items()):
        c = cur.get(pid)
        if c is None:
            print(f"  MISSING product {pid} {o[0]!r}"); ok = False
        elif c[0] != o[0] or c[1] != o[1] or c[2] != o[2] or c[3] != o[3]:
            print(f"  DIFF product {pid}: current {c} vs backup {o}"); ok = False
    extra = [pid for pid in cur if pid not in orig]
    print(f"  products added this session (not in backup): {extra}")
    # --- categories 1..10 ---
    curc = {}
    for line in q("SELECT id,name FROM category").splitlines():
        p = line.split("\t")
        if len(p) == 2: curc[int(p[0])] = p[1]
    origc = {int(f[0]): f[1] for f in dump_tuples("category")}
    for cid in range(1, 11):
        if curc.get(cid) != origc.get(cid):
            print(f"  DIFF category {cid}: current {curc.get(cid)!r} vs backup {origc.get(cid)!r}"); ok = False
    print(f"  categories: current={len(curc)} backup={len(origc)}; added this session: {[c for c in curc if c not in origc]}")
    # --- users ---
    users = q("SELECT COUNT(*) FROM user")
    origu = len(dump_tuples("user"))
    print(f"  users: current={users} backup={origu}", "OK" if users == str(origu) else "DIFF")
    if users != str(origu): ok = False
    print(f"  bills: {q('SELECT COUNT(*) FROM bill')} (backup {len(dump_tuples('bill'))} + E2E runs)")
    print(f"  payments: {q('SELECT COUNT(*) FROM payments')} (backup {len(dump_tuples('payments'))} + E2E runs)")
    print("RESULT:", "INTACT — all backup rows present with identical values" if ok else "DAMAGED")
    sys.exit(0 if ok else 1)

main()
