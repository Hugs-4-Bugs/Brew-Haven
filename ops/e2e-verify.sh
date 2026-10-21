#!/bin/bash
# =============================================================================
# E2E verification v2 — ref-based agent-browser interactions (no silent find).
# Every ab action's output is checked; failures are logged, never swallowed.
# =============================================================================
set -u
: "${CAFEMS_ADMIN_PASSWORD:?CAFEMS_ADMIN_PASSWORD must be set - see ops/env.example}"
PAGE=http://127.0.0.1:3000
SS=/tmp/my-project/artifacts
RL=/tmp/my-project/logs/e2e-results.txt
AB=agent-browser
MARIA="/tmp/my-project/tools/mariadb/bin/mariadb --socket=/tmp/my-project/tools/mariadb-sock/mysqld.sock -u root -N"
: > "$RL"
P_=0; F_=0
p()  { echo "PASS | $1" | tee -a "$RL"; P_=$((P_+1)); }
f()  { echo "FAIL | $1" | tee -a "$RL"; F_=$((F_+1)); }
i()  { echo "INFO | $1" | tee -a "$RL"; }
ab() { agent-browser "$@"; }
# run an ab action, verify it reported success (✓); else log warning
act() { local out; out=$(ab "$@" 2>&1); case "$out" in *"✓"*|*"Done"*) return 0;; *) echo "WARN | ab $* -> $out" | tee -a "$RL"; return 1;; esac; }
# snapshot and extract a ref matching a pattern: ref 'button "Sign in"'
ref() { ab snapshot -i 2>/dev/null | grep -m1 "$1" | grep -oE 'ref=e[0-9]+' | head -1 | sed 's/ref=//'; }
reflast() { ab snapshot -i 2>/dev/null | grep "$1" | tail -1 | grep -oE 'ref=e[0-9]+' | head -1 | sed 's/ref=//'; }
# wait until text appears in the live page body (a11y snapshots omit static <p> text)
waittxt() { local n="${2:-12}" t; for t in $(seq 1 "$n"); do [ "$(ev "document.body.innerText.includes('$1')" 2>/dev/null)" = "true" ] && return 0; sleep 1; done; return 1; }
ev() { ab eval "$1" 2>&1 | sed 's/^"//;s/"$//'; }

echo "========== E2E v2 START $(date -u +%FT%TZ) =========="
act set viewport 1440 900

# ---------------------------------------------------------------- 1. unauth redirect
act open "$PAGE/"
ab eval "localStorage.clear()" >/dev/null 2>&1
act open "$PAGE/"
sleep 2
U=$(ab get url 2>/dev/null)
case "$U" in *"/login"*) p "unauthenticated / redirects to /login";; *) f "unauthenticated / did not redirect (url=$U)";; esac
act screenshot "$SS/verify-00-login.png"

# ---------------------------------------------------------------- 2. bad creds then real login
E=$(ref 'textbox "Email"');     act fill @"$E" "admin@cafe.local"
PW=$(ref 'textbox "Password"'); act fill @"$PW" "wrongpass"
SB=$(ref 'button "Sign in"');   act click @"$SB"
waittxt "Bad Credential" && p "login surfaces backend 'Bad Credential.' on wrong password" || f "bad-credential error not shown"
act fill @"$PW" "$CAFEMS_ADMIN_PASSWORD"
act click @"$SB"
for t in $(seq 1 20); do U=$(ab get url 2>/dev/null); case "$U" in *"/dashboard"*) break;; esac; sleep 1; done
case "$U" in *"/dashboard"*) p "login -> /dashboard";; *) f "login did not reach /dashboard (url=$U)";; esac
act screenshot "$SS/verify-01-dashboard.png"
DASH=$(ab snapshot -c 2>/dev/null)
echo "$DASH" | grep -qi "revenue" && p "dashboard shows revenue analytics (real data)" || f "dashboard revenue missing"
echo "$DASH" | grep -qiE "bill" && p "dashboard shows bill data" || f "dashboard bill data missing"
i "dashboard: $(echo "$DASH" | grep -oE '(Revenue|Bills|Products|Categories|₹ ?[0-9,]+|BEST [A-Za-z ]+)' | head -8 | tr '\n' ' ')"

# ---------------------------------------------------------------- 3. products CRUD
act open "$PAGE/products"; sleep 2
act screenshot "$SS/verify-02-products.png"
PRONAME="E2E Verification Latte"
CATID=$($MARIA -e "USE CafeMgt; SELECT id FROM category WHERE name IN ('Beverages','Beverage','Drinks') LIMIT 1")
[ -z "$CATID" ] && CATID=$($MARIA -e "USE CafeMgt; SELECT id FROM category LIMIT 1")
i "product form category id=$CATID"
AP=$(ref 'Add product'); act click @"$AP"; sleep 1
NM=$(ref 'textbox "Name"'); act fill @"$NM" "$PRONAME"
DS=$(ref 'textbox "Description"'); act fill @"$DS" "Created by live E2E verification run"
PR=$(ref 'textbox "Price'); act fill @"$PR" "60"
CT=$(ref 'combobox "Category"'); act select @"$CT" "$CATID"
SB2=$(reflast 'Add product'); act click @"$SB2"
sleep 2
PRODROW=$($MARIA -e "USE CafeMgt; SELECT id,price,status FROM product WHERE name='$PRONAME' ORDER BY id DESC LIMIT 1")
[ -n "$PRODROW" ] && p "product CREATE persisted in DB ($PRODROW)" || f "product CREATE not in DB"
PID=$(echo "$PRODROW" | awk '{print $1}')
act screenshot "$SS/verify-03-product-added.png"
EB=$(ref 'button "Edit"'); act click @"$EB"; sleep 1
PR=$(ref 'textbox "Price'); act fill @"$PR" "75"
SC=$(ref 'button "Save changes"'); act click @"$SC"; sleep 2
NEWPRICE=$($MARIA -e "USE CafeMgt; SELECT price FROM product WHERE id=$PID")
[ "$NEWPRICE" = "75" ] && p "product UPDATE persisted (price 60->75)" || f "product UPDATE not persisted ($NEWPRICE)"
MU=$(ref 'button "Mark unavailable"'); act click @"$MU"; sleep 2
NEWSTATUS=$($MARIA -e "USE CafeMgt; SELECT status FROM product WHERE id=$PID")
[ "$NEWSTATUS" = "false" ] && p "product status toggle persisted (false)" || f "status toggle not persisted ($NEWSTATUS)"
MA=$(ref 'button "Mark available"'); act click @"$MA"; sleep 2
act screenshot "$SS/verify-04-product-status.png"

# ---------------------------------------------------------------- 4. categories CRUD
act open "$PAGE/categories"; sleep 2
act screenshot "$SS/verify-05-categories.png"
AC=$(ref 'Add category'); act click @"$AC"; sleep 1
CN=$(ref 'textbox "Category name"'); act fill @"$CN" "E2E Temp Category"
CC=$(ref 'button "Create"'); act click @"$CC"; sleep 2
CATROW=$($MARIA -e "USE CafeMgt; SELECT id FROM category WHERE name='E2E Temp Category'")
[ -n "$CATROW" ] && p "category CREATE persisted (id=$CATROW)" || f "category CREATE not in DB"
RN=$(ref 'button "Rename"'); act click @"$RN"; sleep 1
CN=$(ref 'textbox "Category name"'); act fill @"$CN" "E2E Verified"
SV=$(ref 'button "Save"'); act click @"$SV"; sleep 2
RENN=$($MARIA -e "USE CafeMgt; SELECT name FROM category WHERE id=$CATROW")
[ "$RENN" = "E2E Verified" ] && p "category RENAME persisted ('E2E Verified')" || f "category rename not persisted ($RENN)"

# ---------------------------------------------------------------- 5. POS CARD bill
BILLS0=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM bill"); PAY0=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM payments")
i "baseline bills=$BILLS0 payments=$PAY0"
act open "$PAGE/billing"; sleep 2
act screenshot "$SS/verify-06-pos-menu.png"
NITEM=$(ev "document.querySelectorAll('.pos-item').length")
i "menu items available: $NITEM"
ev "document.querySelectorAll('.pos-item')[0].click(); document.querySelectorAll('.pos-item')[1].click(); 'ok'" >/dev/null
sleep 1
PC=$(ref 'button "Proceed to checkout'); act click @"$PC"; sleep 1
act screenshot "$SS/verify-06b-checkout.png"
C1=$(ref 'textbox "Customer name"'); act fill @"$C1" "E2E Customer"
C2=$(ref 'textbox "Contact number"'); act fill @"$C2" "9880012345"
C3=$(ref 'textbox "Email"'); act fill @"$C3" "e2e@cafe.local"
MS=$(ref 'combobox "Payment method"'); act select @"$MS" "CARD"; sleep 1
CN2=$(ref 'textbox "Card number'); act fill @"$CN2" "4111111111111111"
CH=$(ref 'textbox "Card holder name"'); act fill @"$CH" "E2E Customer"
EX=$(ref 'textbox "Expiry'); act fill @"$EX" "12/28"
CV=$(ref 'textbox "CVV"'); act fill @"$CV" "123"
CHG=$(ref 'button "Charge'); act click @"$CHG"
waittxt "Payment recorded" && p "CARD payment recorded in UI" || f "CARD payment UI confirmation missing"
act screenshot "$SS/verify-07-card-payment.png"
GB=$(ref 'button "Generate bill"'); act click @"$GB"
waittxt "generated" && p "CARD bill generated (done stage)" || f "CARD bill generation failed"
act screenshot "$SS/verify-08-card-bill-done.png"
BILL1=$(ev "document.querySelector('h3')?.textContent || ''" | grep -oE "BILL-[0-9]+" | head -1)
i "card bill uuid=$BILL1"
TOK=$(ev "localStorage.getItem('cafe.token') || ''")
PDF1=$(curl -4 -s --noproxy '*' -X POST http://127.0.0.1:8000/bill/getPdf -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "{\"uuid\":\"$BILL1\"}" | head -c 4)
[ "$PDF1" = "%PDF" ] && p "CARD bill PDF served by backend (%PDF)" || f "CARD bill PDF not %PDF ('$PDF1')"

# ---------------------------------------------------------------- 6. POS UPI bill
NB=$(ref 'button "Start new bill"'); act click @"$NB"; sleep 1
ev "document.querySelectorAll('.pos-item')[0].click(); 'ok'" >/dev/null; sleep 1
PC=$(ref 'button "Proceed to checkout'); act click @"$PC"; sleep 1
C1=$(ref 'textbox "Customer name"'); act fill @"$C1" "E2E Customer Two"
C2=$(ref 'textbox "Contact number"'); act fill @"$C2" "9880012346"
MS=$(ref 'combobox "Payment method"'); act select @"$MS" "UPI"; sleep 1
UP=$(ref 'textbox "UPI ID'); act fill @"$UP" "e2euser@ybl"
VF=$(ref 'button "Verify"'); act click @"$VF"
waittxt "same UPI ID" && p "UPI VPA verified in UI (e2euser@ybl)" || f "UPI verify UI failed"
PP=$(ref 'button "Pay'); act click @"$PP"
waittxt "Payment recorded" && p "UPI payment recorded in UI" || f "UPI payment UI confirmation missing"
act screenshot "$SS/verify-09-upi-payment.png"
GB=$(ref 'button "Generate bill"'); act click @"$GB"
waittxt "generated" && p "UPI bill generated" || f "UPI bill generation failed"
act screenshot "$SS/verify-10-upi-bill-done.png"
BILL2=$(ev "document.querySelector('h3')?.textContent || ''" | grep -oE "BILL-[0-9]+" | head -1)
i "upi bill uuid=$BILL2"

# ---------------------------------------------------------------- 7. POS QR bill
NB=$(ref 'button "Start new bill"'); act click @"$NB"; sleep 1
ev "document.querySelectorAll('.pos-item')[2].click(); 'ok'" >/dev/null; sleep 1
PC=$(ref 'button "Proceed to checkout'); act click @"$PC"; sleep 1
C1=$(ref 'textbox "Customer name"'); act fill @"$C1" "E2E Customer Three"
C2=$(ref 'textbox "Contact number"'); act fill @"$C2" "9880012347"
MS=$(ref 'combobox "Payment method"'); act select @"$MS" "UPI_QR"; sleep 1
SQ=$(ref 'button "Show QR'); act click @"$SQ"; sleep 2
QRSRC=$(ev "document.querySelector('.qr-box img')?.getAttribute('src') || 'none'")
case "$QRSRC" in "data:image/png;base64,"*) p "QR is self-contained data-URI PNG";; *) f "QR src not data-URI (${QRSRC:0:50})";; esac
case "$QRSRC" in *http*) f "QR references EXTERNAL http URL (Issue #4 regression)";; *) p "QR has no external http URL (Issue #4 intact)";; esac
act screenshot "$SS/verify-11-qr-payment.png"
CF=$(ref 'button "Customer paid — confirm"'); act click @"$CF"
waittxt "Payment recorded" && p "QR-confirmed payment recorded in UI" || f "QR payment UI confirmation missing"
GB=$(ref 'button "Generate bill"'); act click @"$GB"
waittxt "generated" && p "QR bill generated" || f "QR bill generation failed"
act screenshot "$SS/verify-12-qr-bill-done.png"
BILL3=$(ev "document.querySelector('h3')?.textContent || ''" | grep -oE "BILL-[0-9]+" | head -1)
i "qr bill uuid=$BILL3"

# ---------------------------------------------------------------- 8. bills list + detail
act open "$PAGE/bills"; sleep 2
act screenshot "$SS/verify-13-bills.png"
BL=$(ab snapshot -c 2>/dev/null)
echo "$BL" | grep -q "BILL-" && p "bills list renders bill rows" || f "bills list empty"
VW=$(ref 'button "View"'); act click @"$VW"; sleep 1
act screenshot "$SS/verify-14-bill-detail.png"

# ---------------------------------------------------------------- 9. users (admin)
act open "$PAGE/users"; sleep 2
act screenshot "$SS/verify-15-users.png"
UV=$(ab snapshot -c 2>/dev/null)
echo "$UV" | grep -q "admin@cafe.local" && p "users page lists users (admin visible)" || f "users page missing admin row"

# ---------------------------------------------------------------- 10. logout / guard / re-login
LO=$(ref 'button "Log out"'); act click @"$LO"; sleep 2
U=$(ab get url 2>/dev/null)
case "$U" in *"/login"*) p "logout returns to /login";; *) f "logout did not reach /login (url=$U)";; esac
TOKAFTER=$(ev "localStorage.getItem('cafe.token') || ''")
[ -z "$TOKAFTER" ] && p "token cleared from localStorage on logout" || f "token not cleared after logout"
act open "$PAGE/dashboard"; sleep 2
U=$(ab get url 2>/dev/null)
case "$U" in *"/login"*) p "protected /dashboard redirects to /login when logged out";; *) f "guard failed: /dashboard without session (url=$U)";; esac
E=$(ref 'textbox "Email"');     act fill @"$E" "admin@cafe.local"
PW=$(ref 'textbox "Password"'); act fill @"$PW" "$CAFEMS_ADMIN_PASSWORD"
SB=$(ref 'button "Sign in"');   act click @"$SB"
for t in $(seq 1 20); do U=$(ab get url 2>/dev/null); case "$U" in *"/dashboard"*) break;; esac; sleep 1; done
case "$U" in *"/dashboard"*) p "re-login works";; *) f "re-login failed (url=$U)";; esac

# ---------------------------------------------------------------- 11. direct reloads with session
for R in / /dashboard /products /categories /billing /bills /users /excel-import /change-password /login; do
  act open "$PAGE$R"; sleep 2
  U=$(ab get url 2>/dev/null)
  case "$U" in "$PAGE$R"*) p "direct reload $R serves SPA (no redirect/404)";; *) f "direct reload $R -> $U";; esac
done
act open "$PAGE/dashboard"; sleep 2
act screenshot "$SS/verify-16-final-dashboard.png"

# ---------------------------------------------------------------- 12. console/network
CERR=$(ab errors 2>/dev/null | sed -n '1,5p')
[ -z "$(echo "$CERR" | grep -v '^$')" ] && p "zero page console errors" || { f "console errors present"; echo "$CERR" | tee -a "$RL"; }
NET=$(ab network requests 2>/dev/null)
i "browser network log (non-2xx): $(echo "$NET" | grep -E '^(GET|POST|PUT|DELETE)' | grep -vE ' (200|201|204|302|304) ' | head -5 | tr '\n' ' ')"

# ---------------------------------------------------------------- 13. DB persistence
NEWPAY=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM payments"); NEWBILLS=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM bill")
i "payments $PAY0 -> $NEWPAY | bills $BILLS0 -> $NEWBILLS"
[ "$NEWPAY" -ge $((PAY0+3)) ] && p "3+ payments persisted (CARD+UPI+QR)" || f "payments not persisted ($PAY0->$NEWPAY)"
[ "$NEWBILLS" -ge $((BILLS0+3)) ] && p "3 bills persisted" || f "bills not persisted ($BILLS0->$NEWBILLS)"
UPIROW=$($MARIA -e "USE CafeMgt; SELECT CONCAT('type=',payment_type,' upi=',IFNULL(upi_id,'NULL'),' amt=',amount) FROM payments WHERE upi_id='e2euser@ybl' ORDER BY id DESC LIMIT 1")
[ -n "$UPIROW" ] && p "UPI upi_id persisted ($UPIROW) — Issue #3 mapping intact" || { f "UPI upi_id not persisted"; i "$UPIROW"; }
QROW=$($MARIA -e "USE CafeMgt; SELECT CONCAT('type=',payment_type,' upi=',IFNULL(upi_id,'NULL'),' amt=',amount) FROM payments WHERE upi_id LIKE 'merchant-qr%' ORDER BY id DESC LIMIT 1")
[ -n "$QROW" ] && p "QR-sourced payment persisted ($QROW)" || f "QR payment row not found"
CARDROW=$($MARIA -e "USE CafeMgt; SHOW COLUMNS FROM payments" | grep -oE "card_[a-z_]+" | head -3 | tr '\n' ' ')
i "payments card columns: $CARDROW"
STOREPDF=$(ls /tmp/my-project/artifacts/bills/*.pdf 2>/dev/null | wc -l)
NEWSince=$(find /tmp/my-project/artifacts/bills -name "BILL-*.pdf" -newermt "-30 minutes" 2>/dev/null | wc -l)
[ "$NEWSince" -ge 3 ] && p "new bill PDFs written by CAFE_BILL_STORE_LOCATION ($NEWSince in last 30min)" || f "no new PDFs in store ($STOREPDF total)"

# ---------------------------------------------------------------- 14. cleanup: delete E2E product via UI
act open "$PAGE/products"; sleep 2
DB1=$(ref 'button "Delete"'); act click @"$DB1"; sleep 1
DB2=$(reflast 'button "Delete"'); act click @"$DB2"; sleep 2
STILL=$($MARIA -e "USE CafeMgt; SELECT COUNT(*) FROM product WHERE id=$PID")
[ "$STILL" = "0" ] && p "product DELETE persisted (menu clean)" || f "product delete not persisted ($STILL)"

ab close
echo "========== E2E v2 SUMMARY: PASS=$P_ FAIL=$F_ =========="
grep "^FAIL" "$RL" || echo "(no failures)"
