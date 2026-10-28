-- M6b Quick Add (spec 3.3 muc 3.2, 13; contract muc 2, 8.2).
-- quick_add_chips: cac chip noi bang dau phay, thu tu = thu tu hien thi, chip vang = an; PROJECT luon co.
-- Trung chip do app kiem (QuickAddChipRules), CHECK chi chan dinh dang + ten chip + co PROJECT.
-- quick_add_show_action_labels / quick_add_show_description: "Show action labels", "Show description" (mac dinh bat).
ALTER TABLE users
    ADD COLUMN quick_add_chips              TEXT    NOT NULL DEFAULT 'PROJECT,DATE,ASSIGNEE',
    ADD COLUMN quick_add_show_action_labels BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN quick_add_show_description   BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users
    ADD CONSTRAINT ck_users_quick_add_chips CHECK (
        quick_add_chips ~ '^(PROJECT|DATE|ASSIGNEE|ATTACHMENT|PRIORITY|REMINDERS|LABELS|DEADLINE|LOCATION)(,(PROJECT|DATE|ASSIGNEE|ATTACHMENT|PRIORITY|REMINDERS|LABELS|DEADLINE|LOCATION)){0,8}$'
        AND (',' || quick_add_chips || ',') LIKE '%,PROJECT,%');
