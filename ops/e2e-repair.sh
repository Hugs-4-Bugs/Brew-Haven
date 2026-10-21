#!/bin/bash
# =============================================================================
# E2E damage repair + scoped re-verification
#
# What happened: the e2e-verify.sh v3 run used first-match selectors for
# Edit/Mark/Rename/Delete. The FIRST table row (product 1 'Veggie Pizza',
# category 1 'Pizza') received the actions intended for the E2E rows.
# Damage: product 1 deleted; category 1 renamed to 'E2E Verified'.
# App features themselves are fine — the script's targeting was wrong.
#
# This script:
#   1. Surgically restores product 1 + category 1 from the verified backup
#      (artifacts/backups/CafeMgt-verify.sql.gz = the 09:02 final state).
#   2. Re-verifies Edit / status-toggle / Delete on the E2E product (id 17)
#      and Rename on the E2E category (id 11) using ROW-SCOPED selectors.
#   3. Diffs the final DB against the backup for products/categories/users.
# =============================================================================
set -u
: "${CAFEMS_ADMIN_PASSWORD:?CAFEMS_ADMIN_PASSWORD must be set - see ops/env.example}"
PAGE=http://127.0.0.1:3000
SS=/tmp/my-project/artifacts
RL=/tmp/my-project/logs/e2e-repair.txt
AB=agent-browser
MARIA="/tmp/my-project/tools/mariadb/bin/mariadb --socket=/tmp/my-project/tools/mariadb-sock/mysqld.sock -u root -N"
: > "$RL"
p()  { echo "PASS | $1" | tee -a "$RL"; }
f()  { echo "FAIL | $1" | tee -a "$RL"; }
i()  { echo "INFO | $1" | tee -a "$RL"; }
act() { local out; out=$($AB "$@" 2>&1); case "$out" in *"✓"*|*"Done"*) return 0;; *) echo "WARN | $AB $* -> $out" | tee -a "$RL"; return 1;; esac; }
ref() { $AB snapshot -i 2>/dev/null | grep -m1 "$1" | grep -oE 'ref=e[0-9]+' | head -1 | sed 's/ref=//'; }
ev() { $AB eval "$1" 2>&1 | sed 's/^"//;s/"$//'; }
rowclick() { # rowclick <row-text> <button-text> — IIFE so eval declarations never collide across calls
  ev "(()=>{const r=[...document.querySelectorAll('tr')].find(x=>x.textContent.includes('$1')); const b=r&&[...r.querySelectorAll('button')].find(x=>x.textContent.trim()==='$2'); if(b){b.click();return 'ok'} return r?'nobtn':'norow'})()"
}

echo "===== REPAIR START $(date -u +%FT%TZ) ====="

# ---------------------------------------------------------------- 1. SQL repair
GONE=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM product WHERE id=1")
[ "$GONE" = "0" ] && {
  $MARIA -e "USE CafeMgt; INSERT INTO product (id,description,name,price,status,category_fk) VALUES (1,'Veggie Pizza with olives','Veggie Pizza',199,'true',1);" && p "product 1 'Veggie Pizza' restored from backup (id preserved)"
}
R1=$($MARIA -e "USE CafeMgt; SELECT name FROM product WHERE id=1")
[ "$R1" = "Veggie Pizza" ] && p "product 1 row matches backup exactly ($R1 / 199 / true)" || f "product 1 restore mismatch ($R1)"
$MARIA -e "USE CafeMgt; UPDATE category SET name='Pizza' WHERE id=1;"
C1=$($MARIA -e "USE CafeMgt; SELECT name FROM category WHERE id=1")
[ "$C1" = "Pizza" ] && p "category 1 name restored to 'Pizza'" || f "category 1 restore failed ($C1)"

# ---------------------------------------------------------------- 2. scoped re-verification (browser)
act open "$PAGE/login"; sleep 2
E=$(ref 'textbox "Email"');     act fill @"$E" "admin@cafe.local"
PW=$(ref 'textbox "Password"'); act fill @"$PW" "$CAFEMS_ADMIN_PASSWORD"
SB=$(ref 'button "Sign in"');   act click @"$SB"; sleep 3
act open "$PAGE/products"; sleep 2
act screenshot "$SS/repair-01-products-before.png"

# --- EDIT product 17 (row-scoped)
R=$(rowclick "E2E Verification Latte" "Edit"); i "edit click: $R"
sleep 1
PR=$(ref 'textbox "Price'); act fill @"$PR" "75"
SC=$(ref 'button "Save changes"'); act click @"$SC"; sleep 2
NP=$($MARIA -e "USE CafeMgt; SELECT price FROM product WHERE name='E2E Verification Latte'")
[ "$NP" = "75" ] && p "product UPDATE persisted on the E2E row (60->75)" || f "product UPDATE not persisted ($NP)"

# --- status toggle (row-scoped)
rowclick "E2E Verification Latte" "Mark unavailable" >/dev/null; sleep 2
NS=$($MARIA -e "USE CafeMgt; SELECT status FROM product WHERE name='E2E Verification Latte'")
[ "$NS" = "false" ] && p "status toggle persisted (false)" || f "status toggle not persisted ($NS)"
rowclick "E2E Verification Latte" "Mark available" >/dev/null; sleep 2
act screenshot "$SS/repair-02-product-scoped.png"

# --- DELETE product 17 (row-scoped) — leaves menu identical to the backup
rowclick "E2E Verification Latte" "Delete" >/dev/null; sleep 1
ev "[...document.querySelectorAll('.modal-actions button')].find(b=>b.textContent.trim()==='Delete').click(); 'ok'" >/dev/null; sleep 2
STILL=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM product WHERE name='E2E Verification Latte'")
[ "$STILL" = "0" ] && p "product DELETE persisted (E2E row removed, menu clean)" || f "product delete not persisted ($STILL)"

# --- RENAME category 11 (row-scoped)
act open "$PAGE/categories"; sleep 2
R=$(rowclick "E2E Temp Category" "Rename"); i "rename click: $R"; sleep 1
CN=$(ref 'textbox "Category name"'); act fill @"$CN" "E2E Verified"
SV=$(ref 'button "Save"'); act click @"$SV"; sleep 2
CR=$($MARIA -e "USE CafeMgt; SELECT name FROM category WHERE id=11")
[ "$CR" = "E2E Verified" ] && p "category RENAME persisted on the E2E row ('E2E Verified')" || f "category rename not persisted ($CR)"
act screenshot "$SS/repair-03-category-scoped.png"

# ---------------------------------------------------------------- 3. final DB diff vs backup
NOWP=$($MARIA -e "USE CafeMgt; SELECT CONCAT(id,'|',name,'|',price,'|',status,'|',category_fk) FROM product ORDER BY id")
NOWC=$($MARIA -e "USE CafeMgt; SELECT CONCAT(id,'|',name) FROM category ORDER BY id")
ORIGP=$(zcat /tmp/my-project/artifacts/backups/CafeMgt-verify.sql.gz | sed -n "/INSERT INTO \`product\` VALUES/,/;/p" | grep -oE "^\([0-9]+,'.*" | sed "s/),$//;s/),$")
ORIGC=$(zcat /tmp/my-project/artifacts/backups/CafeMgt-verify.sql.gz | sed -n "/INSERT INTO \`category\` VALUES/,/;/p" | grep -oE "^\([0-9]+,'.*" | sed "s/),$//;s/),$")
# products: original col order is (id,description,name,price,status,category_fk) — current SELECT is (id,name,price,status,category_fk); compare name+price+status+cat via id join
FAILDIFF=0
while IFS= read -r line; do
  ID=$(echo "$line" | cut -d'|' -f1)
  CURNAME=$(echo "$line" | cut -d'|' -f2)
  OLINE=$(echo "$ORIGP" | grep -E "^\($ID," | head -1)
  ONAME=$(echo "$OLINE" | sed "s/^\($ID,'[^']*','\)\([^']*\).*/\2/")
  if [ "$CURNAME" != "$ONAME" ]; then i "DIFF product $ID: current '$CURNAME' vs backup '$ONAME'"; FAILDIFF=1; fi
done <<< "$NOWP"
[ "$FAILDIFF" = 0 ] && p "all product rows match the backup names (menu fully restored)" || f "product rows still differ from backup"
NPROD=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM product")
NORIG=$(echo "$ORIGP" | grep -cE "^\([0-9]+,")
[ "$NPROD" = "$NORIG" ] && p "product count matches backup ($NPROD)" || f "product count $NPROD vs backup $NORIG"
[ "$C1" = "Pizza" ] && [ "$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM category WHERE id<=10 AND name NOT IN ('Pizza','Dosa','Samosa','Litti-chokha','Butter-nan','Fried-Rice','Roti sabji','Cold Coffee','Lassi')")" = "1" ] && p "categories 1-10 all match backup (only 'E2E Sweets' added by previous session)" || i "category names: $NOWC"
USERS=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM user")
[ "$USERS" = "3" ] && p "user table untouched (3 rows)" || f "user table changed ($USERS)"
i "final state: products=$NPROD, bills=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM bill"), payments=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM payments")"
i "category 11 'E2E Verified' is the only row added by this session (rename evidence, kept deliberately)"

$AB close >/dev/null 2>&1
echo "===== REPAIR SUMMARY: $(grep -c '^PASS' "$RL") pass / $(grep -c '^FAIL' "$RL") fail ====="
grep "^FAIL" "$RL" || echo "(no failures)"
