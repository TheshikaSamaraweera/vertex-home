-- ==============================================================================================
-- Home furniture catalogue — opening data for a new deployment.
--
--   docker compose exec -T db psql -U postgres -d mlmsitty < mlmsittu/db/seed/furniture-catalogue.sql
--
-- Categories, suppliers, items with supplier prices, five reward packs, and opening stock in the
-- main warehouse.
--
-- NOT a migration, and deliberately not in db/migration. Migrations are schema and run on every
-- deployment automatically; this is a starting catalogue somebody chose, and the next server may
-- want a different one. Flyway must never own it.
--
-- Safe to run twice. Every insert is keyed on a natural code — sku, category code, supplier code,
-- set code — and conflicts are ignored, so a re-run adds what is missing and changes nothing that
-- already exists. Opening stock is the one thing guarded separately: see section 6.
--
-- Prices are in LKR and illustrative. Change them in the application once you are in; this is a
-- starting point, not a price list to maintain here.
-- ==============================================================================================

BEGIN;

-- ----------------------------------------------------------------------------------------------
-- Who this data belongs to.
--
-- stock_movement.created_by and item_supplier_price.created_by are NOT NULL and reference a real
-- person, because "who put this number here" is the question those tables exist to answer. The
-- first SUPER_ADMIN stands in for the office.
-- ----------------------------------------------------------------------------------------------
CREATE TEMP TABLE seed_ctx ON COMMIT DROP AS
SELECT (SELECT u.id
          FROM app_user u
          JOIN user_role ur ON ur.user_id = u.id
          JOIN app_role  r  ON r.id = ur.role_id
         WHERE r.code = 'SUPER_ADMIN'
         ORDER BY u.created_at
         LIMIT 1)                                        AS admin_id,
       (SELECT id FROM location WHERE is_default LIMIT 1) AS location_id;

DO $seed$
BEGIN
    IF (SELECT admin_id FROM seed_ctx) IS NULL THEN
        RAISE EXCEPTION
            'No SUPER_ADMIN exists yet. Create the first administrator (DEPLOYMENT.md section 6) '
            'before seeding — every row here records who entered it.';
    END IF;
    IF (SELECT location_id FROM seed_ctx) IS NULL THEN
        RAISE EXCEPTION 'No default location. V5 seeds MAIN; has it been altered?';
    END IF;
END
$seed$;


-- ----------------------------------------------------------------------------------------------
-- 1 · Categories
-- ----------------------------------------------------------------------------------------------
INSERT INTO category (code, name) VALUES
    ('LIVING',  'Living Room'),
    ('BEDROOM', 'Bedroom'),
    ('DINING',  'Dining'),
    ('OFFICE',  'Home Office'),
    ('STORAGE', 'Storage'),
    ('KITCHEN', 'Kitchen and Utility')
ON CONFLICT (code) DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 2 · Suppliers
-- ----------------------------------------------------------------------------------------------
INSERT INTO supplier (code, name, contact_name, email, phone, address) VALUES
    ('SUP-TIMBER', 'Ceylon Timber Works',    'Nuwan Perera',     'orders@ceylontimber.lk', '+94112345601', 'Moratuwa'),
    ('SUP-UPHOL',  'Lanka Upholstery House', 'Ishara Silva',     'sales@lankauphol.lk',    '+94112345602', 'Panadura'),
    ('SUP-STEEL',  'Metro Steel Furniture',  'Kasun Fernando',   'info@metrosteel.lk',     '+94112345603', 'Kelaniya'),
    ('SUP-IMPORT', 'Colombo Home Imports',   'Dilani Jayasuriya','buying@colombohome.lk',  '+94112345604', 'Colombo 05')
ON CONFLICT (code) DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 3 · Items
--
-- unit_cost is what it costs to acquire; selling_price is the retail reference. Both are
-- reference figures — a sale takes its price from the order line, not from here, so changing a
-- price never rewrites history.
-- ----------------------------------------------------------------------------------------------
INSERT INTO item (sku, name, description, category_id, unit_cost, selling_price, reorder_level)
SELECT v.sku, v.name, v.description, c.id, v.unit_cost, v.selling_price, v.reorder_level
  FROM (VALUES
    -- Living room
    ('FRN-SOFA-3S', 'Three Seater Sofa',      'Fabric upholstered, teak frame',   'LIVING',   85000.00, 129000.00,  3),
    ('FRN-SOFA-2S', 'Two Seater Sofa',        'Fabric upholstered, teak frame',   'LIVING',   62000.00,  94000.00,  3),
    ('FRN-ARMCHR',  'Armchair',               'Single seat, matching sofa range', 'LIVING',   34000.00,  52000.00,  4),
    ('FRN-COFTBL',  'Coffee Table',           'Mahogany top, 90 x 50 cm',         'LIVING',   18500.00,  28000.00,  5),
    ('FRN-TVSTND',  'TV Stand',               'Two drawers, cable routing',       'LIVING',   24000.00,  37000.00,  4),
    ('FRN-SHOERK',  'Shoe Rack',              'Four tier, closed front',          'LIVING',    9500.00,  15500.00,  8),
    -- Bedroom
    ('FRN-BED-QN',  'Queen Bed Frame',        'Teak, 60 x 78 inch',               'BEDROOM',  78000.00, 118000.00,  3),
    ('FRN-BED-SG',  'Single Bed Frame',       'Teak, 36 x 75 inch',               'BEDROOM',  46000.00,  71000.00,  4),
    ('FRN-MATT-QN', 'Queen Mattress',         'Spring, 8 inch',                   'BEDROOM',  42000.00,  65000.00,  5),
    ('FRN-MATT-SG', 'Single Mattress',        'Spring, 6 inch',                   'BEDROOM',  24000.00,  37500.00,  6),
    ('FRN-WARD-3D', 'Three Door Wardrobe',    'Mirror on centre door',            'BEDROOM',  96000.00, 145000.00,  2),
    ('FRN-WARD-2D', 'Two Door Wardrobe',      'Hanging rail and two shelves',     'BEDROOM',  68000.00, 103000.00,  3),
    ('FRN-DRESS',   'Dressing Table',         'Mirror and three drawers',         'BEDROOM',  32000.00,  49000.00,  4),
    ('FRN-BEDSD',   'Bedside Table',          'Single drawer, open shelf',        'BEDROOM',   9800.00,  15900.00, 10),
    -- Dining
    ('FRN-DINE-6',  'Six Seater Dining Set',  'Table with six chairs',            'DINING',  118000.00, 178000.00,  2),
    ('FRN-DINE-4',  'Four Seater Dining Set', 'Table with four chairs',           'DINING',   82000.00, 125000.00,  3),
    ('FRN-DCHAIR',  'Dining Chair',           'Cushioned seat, sold singly',      'DINING',    8500.00,  13500.00, 12),
    ('FRN-SIDEBD',  'Sideboard',              'Four doors, two drawers',          'DINING',   54000.00,  82000.00,  3),
    -- Home office
    ('FRN-DESK',    'Office Desk',            '120 x 60 cm, two drawers',         'OFFICE',   28000.00,  43000.00,  5),
    ('FRN-OFCHR',   'Office Chair',           'Mesh back, height adjustable',     'OFFICE',   17500.00,  27500.00,  6),
    ('FRN-BOOKSH',  'Bookshelf',              'Five tier, open back',             'OFFICE',   19000.00,  29500.00,  5),
    ('FRN-FILECB',  'Filing Cabinet',         'Steel, three drawers, lockable',   'OFFICE',   23000.00,  35500.00,  4),
    -- Storage
    ('FRN-ALMIRA',  'Steel Almirah',          'Two door, lockable',               'STORAGE',  46000.00,  70000.00,  3),
    ('FRN-STORRK',  'Storage Rack',           'Five tier, powder coated',         'STORAGE',  12500.00,  19500.00,  8),
    -- Kitchen and utility
    ('FRN-PANTRY',  'Pantry Cupboard',        'Four door, adjustable shelves',    'KITCHEN',  58000.00,  88000.00,  3),
    ('FRN-KTCHTR',  'Kitchen Trolley',        'Three tier, castor wheels',        'KITCHEN',  14500.00,  22500.00,  6)
  ) AS v(sku, name, description, category_code, unit_cost, selling_price, reorder_level)
  JOIN category c ON c.code = v.category_code
ON CONFLICT (sku) DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 4 · Supplier prices
--
-- What each supplier charges, which is what a purchase order quotes. Distinct from item.unit_cost:
-- that is one number for valuation, these are per-supplier and may differ.
-- ----------------------------------------------------------------------------------------------
INSERT INTO item_supplier_price (item_id, supplier_id, price, note, created_by)
SELECT i.id, s.id, ROUND(i.unit_cost * v.factor, 2), v.note, (SELECT admin_id FROM seed_ctx)
  FROM (VALUES
    ('FRN-SOFA-3S', 'SUP-UPHOL',  1.00, 'Standard fabric'),
    ('FRN-SOFA-2S', 'SUP-UPHOL',  1.00, 'Standard fabric'),
    ('FRN-ARMCHR',  'SUP-UPHOL',  1.00, NULL),
    ('FRN-COFTBL',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-TVSTND',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-SHOERK',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-BED-QN',  'SUP-TIMBER', 1.00, 'Teak'),
    ('FRN-BED-SG',  'SUP-TIMBER', 1.00, 'Teak'),
    ('FRN-MATT-QN', 'SUP-IMPORT', 1.00, NULL),
    ('FRN-MATT-SG', 'SUP-IMPORT', 1.00, NULL),
    ('FRN-WARD-3D', 'SUP-TIMBER', 1.00, NULL),
    ('FRN-WARD-2D', 'SUP-TIMBER', 1.00, NULL),
    ('FRN-DRESS',   'SUP-TIMBER', 1.00, NULL),
    ('FRN-BEDSD',   'SUP-TIMBER', 1.00, NULL),
    ('FRN-DINE-6',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-DINE-4',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-DCHAIR',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-SIDEBD',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-DESK',    'SUP-IMPORT', 1.00, NULL),
    ('FRN-OFCHR',   'SUP-IMPORT', 1.00, NULL),
    ('FRN-BOOKSH',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-FILECB',  'SUP-STEEL',  1.00, NULL),
    ('FRN-ALMIRA',  'SUP-STEEL',  1.00, NULL),
    ('FRN-STORRK',  'SUP-STEEL',  1.00, NULL),
    ('FRN-PANTRY',  'SUP-TIMBER', 1.00, NULL),
    ('FRN-KTCHTR',  'SUP-STEEL',  1.00, NULL),
    -- A second quote on the big-ticket lines, so the purchase-order screen has a choice to make.
    ('FRN-SOFA-3S', 'SUP-IMPORT', 1.06, 'Imported alternative, longer lead time'),
    ('FRN-WARD-3D', 'SUP-IMPORT', 1.08, 'Imported alternative, longer lead time'),
    ('FRN-DINE-6',  'SUP-IMPORT', 1.05, 'Imported alternative, longer lead time')
  ) AS v(sku, supplier_code, factor, note)
  JOIN item     i ON i.sku  = v.sku
  JOIN supplier s ON s.code = v.supplier_code
ON CONFLICT DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 5 · Reward packs
--
-- What a customer chooses at registration and receives once all five referral stages are done.
-- set_price is below the sum of the parts, which is the point of a pack.
-- ----------------------------------------------------------------------------------------------
INSERT INTO item_set (code, name, description, set_price) VALUES
    ('PACK-STARTER', 'Starter Home Pack', 'Coffee table, shoe rack, two bedside tables and a storage rack.',  74000.00),
    ('PACK-LIVING',  'Living Room Pack',  'Two seater sofa, coffee table and a TV stand.',                   142000.00),
    ('PACK-BEDROOM', 'Bedroom Pack',      'Single bed, mattress, two door wardrobe and a bedside table.',    215000.00),
    ('PACK-DINING',  'Dining Pack',       'Four seater dining set and a sideboard.',                         192000.00),
    ('PACK-OFFICE',  'Home Office Pack',  'Desk, office chair, bookshelf and a filing cabinet.',             122000.00)
ON CONFLICT (code) DO NOTHING;

INSERT INTO item_set_line (set_id, item_id, quantity)
SELECT s.id, i.id, v.quantity
  FROM (VALUES
    ('PACK-STARTER', 'FRN-COFTBL', 1),
    ('PACK-STARTER', 'FRN-SHOERK', 1),
    ('PACK-STARTER', 'FRN-BEDSD',  2),
    ('PACK-STARTER', 'FRN-STORRK', 1),

    ('PACK-LIVING',  'FRN-SOFA-2S', 1),
    ('PACK-LIVING',  'FRN-COFTBL',  1),
    ('PACK-LIVING',  'FRN-TVSTND',  1),

    ('PACK-BEDROOM', 'FRN-BED-SG',  1),
    ('PACK-BEDROOM', 'FRN-MATT-SG', 1),
    ('PACK-BEDROOM', 'FRN-WARD-2D', 1),
    ('PACK-BEDROOM', 'FRN-BEDSD',   1),

    ('PACK-DINING',  'FRN-DINE-4',  1),
    ('PACK-DINING',  'FRN-SIDEBD',  1),

    ('PACK-OFFICE',  'FRN-DESK',   1),
    ('PACK-OFFICE',  'FRN-OFCHR',  1),
    ('PACK-OFFICE',  'FRN-BOOKSH', 1),
    ('PACK-OFFICE',  'FRN-FILECB', 1)
  ) AS v(set_code, sku, quantity)
  JOIN item_set s ON s.code = v.set_code
  JOIN item     i ON i.sku  = v.sku
ON CONFLICT ON CONSTRAINT uq_item_set_line DO NOTHING;


-- ----------------------------------------------------------------------------------------------
-- 6 · Opening stock
--
-- Two writes, and both are required. stock_movement is the ledger — every change to a quantity,
-- ever, with who and why. stock_level is the running total the application reads. Writing one
-- without the other leaves the ledger and the balance disagreeing, and the ledger is the side
-- believed when they are reconciled.
--
-- Only for items with no movement yet, so a second run adds nothing. Without that guard a re-run
-- would book a second opening balance and quietly double every quantity.
-- ----------------------------------------------------------------------------------------------
INSERT INTO stock_movement (item_id, location_id, qty_delta, movement_type, reason, note, created_by)
SELECT i.id,
       (SELECT location_id FROM seed_ctx),
       v.qty,
       'OPENING_BALANCE',
       'SEED',
       'Opening catalogue',
       (SELECT admin_id FROM seed_ctx)
  FROM (VALUES
    ('FRN-SOFA-3S', 6), ('FRN-SOFA-2S', 8), ('FRN-ARMCHR', 10), ('FRN-COFTBL', 14),
    ('FRN-TVSTND', 10), ('FRN-SHOERK', 20), ('FRN-BED-QN', 6),  ('FRN-BED-SG', 10),
    ('FRN-MATT-QN', 8), ('FRN-MATT-SG', 12),('FRN-WARD-3D', 5), ('FRN-WARD-2D', 8),
    ('FRN-DRESS', 9),   ('FRN-BEDSD', 24),  ('FRN-DINE-6', 4),  ('FRN-DINE-4', 6),
    ('FRN-DCHAIR', 30), ('FRN-SIDEBD', 7),  ('FRN-DESK', 12),   ('FRN-OFCHR', 15),
    ('FRN-BOOKSH', 11), ('FRN-FILECB', 9),  ('FRN-ALMIRA', 7),  ('FRN-STORRK', 18),
    ('FRN-PANTRY', 6),  ('FRN-KTCHTR', 13)
  ) AS v(sku, qty)
  JOIN item i ON i.sku = v.sku
 WHERE NOT EXISTS (SELECT 1 FROM stock_movement m WHERE m.item_id = i.id);

INSERT INTO stock_level (item_id, location_id, on_hand, reserved)
SELECT m.item_id, m.location_id, SUM(m.qty_delta), 0
  FROM stock_movement m
 WHERE m.reason = 'SEED'
 GROUP BY m.item_id, m.location_id
ON CONFLICT (item_id, location_id) DO NOTHING;

COMMIT;


-- ----------------------------------------------------------------------------------------------
-- What went in.
-- ----------------------------------------------------------------------------------------------
SELECT 'categories'      AS loaded, count(*)::TEXT AS n FROM category
UNION ALL SELECT 'suppliers',       count(*)::TEXT FROM supplier
UNION ALL SELECT 'items',           count(*)::TEXT FROM item
UNION ALL SELECT 'supplier prices', count(*)::TEXT FROM item_supplier_price
UNION ALL SELECT 'reward packs',    count(*)::TEXT FROM item_set
UNION ALL SELECT 'pack lines',      count(*)::TEXT FROM item_set_line
UNION ALL SELECT 'units in stock',  COALESCE(SUM(on_hand), 0)::TEXT FROM stock_level;
