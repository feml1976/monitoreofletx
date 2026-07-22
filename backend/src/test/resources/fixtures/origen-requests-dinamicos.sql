-- Fixtures DINAMICOS de requests/events/consecutive_ministries/comply_destinations/
-- liquidations para OrigenAdapterTest. Estas fechas usan now() de PostgreSQL pero
-- se ejecutan en @BeforeAll (tiempo de test), NO en el initdb del contenedor --
-- por eso quedan siempre dentro de la ventana de sync sin importar cuanto lleve
-- vivo el contenedor de test (leccion Etapa 6/D1 de Controlt). Idempotente:
-- DELETE + INSERT, para poder correr la suite varias veces sin reiniciar el
-- contenedor.
SET search_path TO test_origen_mf;

DELETE FROM liquidations           WHERE request_id IN (2001, 2002, 2003, 2004);
DELETE FROM comply_destinations    WHERE request_id IN (2001, 2002, 2003, 2004);
DELETE FROM consecutive_ministries WHERE request_id IN (2001, 2002, 2003, 2004);
DELETE FROM events                 WHERE request_id IN (2001, 2002, 2003, 2004);
DELETE FROM requests                WHERE id         IN (2001, 2002, 2003, 2004);

-- Request 2001: "happy path" — booking con planta origen/destino 1:1, vehiculo +
-- trailer + conductor + propietarios/tenedores, cumplidos, liquidacion, y 3
-- eventos (incluye el evento genesis con estado_anterior NULL, ver [B10]).
INSERT INTO requests (id, created_at, updated_at, booking_id, requeststatus_id, driver_id,
                       vehicle_id, trailer_id, loadgenerator_id, transport_company_id,
                       name_receives, phone_receives, another_receives, loading_date,
                       freight, value_pay_to_driver, load_weight, load_units,
                       ingresoid_remesa, ingresoid_manifest, seguridadqr_manifest,
                       sinister, standby, exclusive_fleet, seconds_to_destiny,
                       has_auction, load_id, trip_id, operation_type)
    VALUES (2001, now() - interval '1 day', now() - interval '12 hours', 3001, 10, 401,
            501, 601, 1, 1,
            'Ana Receptora', '3011112222', true, now() - interval '20 hours',
            5000000, 1234567.89, 32000, 1,
            'REM-001', 'MAN-001', 'QR-001',
            false, 2, false, 36000,
            true, 9001, 9101, 'Normal');

INSERT INTO events (id, request_id, requeststatatusold_id, requeststatus_id, created_at, comment) VALUES
    (1, 2001, NULL, 1,  now() - interval '1 day',      'Solicitud creada'),   -- genesis: sin estado_anterior
    (2, 2001, 1,    7,  now() - interval '20 hours',   'Cargue completo'),
    (3, 2001, 7,    10, now() - interval '12 hours',   NULL);

INSERT INTO consecutive_ministries (id, request_id, consecutive_manifest, consecutive_remesa)
    VALUES (1, 2001, 10001, 20001);

INSERT INTO comply_destinations (id, request_id, accomplished, annulled, date_comply) VALUES
    (1, 2001, true, false, now() - interval '10 hours'),
    (2, 2001, true, false, now() - interval '9 hours');

INSERT INTO liquidations (id, request_id, consecutive, advance, balance, deductions,
                           agreed_value_to_pay_driver, was_paid, legalized, date_annulled)
    VALUES (1, 2001, 555, 100000, 50000, 5000, 1200000, true, true, NULL);

-- Request 2002: sin booking, sin vehiculo/trailer/conductor — todos los LEFT JOIN
-- deben resolver a NULL sin romper el mapeo (no debe lanzar NPE ni fallar el RowMapper).
INSERT INTO requests (id, created_at, updated_at, booking_id, requeststatus_id, driver_id,
                       vehicle_id, trailer_id, loadgenerator_id, standby, sinister,
                       another_receives, has_auction)
    VALUES (2002, now() - interval '2 hours', now() - interval '1 hour', NULL, 1, NULL,
            NULL, NULL, NULL, 0, false,
            false, true);

INSERT INTO events (id, request_id, requeststatatusold_id, requeststatus_id, created_at, comment) VALUES
    (4, 2002, NULL, 1, now() - interval '2 hours', NULL);

-- Request 2003: booking 3003, multi-recogida (2 direcciones de carga + 1 de
-- descarga) -- planta_origen debe concatenar en orden alfabetico.
INSERT INTO requests (id, created_at, updated_at, booking_id, requeststatus_id, standby, sinister,
                       another_receives, has_auction)
    VALUES (2003, now() - interval '3 days', now() - interval '3 days' + interval '4 hours',
            3003, 7, 0, false, false, true);

INSERT INTO events (id, request_id, requeststatatusold_id, requeststatus_id, created_at, comment) VALUES
    (5, 2003, NULL, 1, now() - interval '3 days', NULL),
    (6, 2003, 1,    7, now() - interval '3 days' + interval '4 hours', NULL);

-- Request 2004: cardinalidad 1:N en consecutive_ministries (anomalia real de
-- Fletx prod, ya mordio a Controlt). Fila id=10 (real, consecutivos no nulos) y
-- fila id=11 (huerfana, ambos NULL, id mayor = mas reciente). El CTE 'ministries'
-- con DISTINCT ON debe devolver UNA fila con los datos reales, no la huerfana.
INSERT INTO requests (id, created_at, updated_at, booking_id, requeststatus_id, standby, sinister,
                       another_receives, has_auction)
    VALUES (2004, now() - interval '4 days', now() - interval '4 days' + interval '1 hour',
            3001, 10, 0, false, false, true);

INSERT INTO events (id, request_id, requeststatatusold_id, requeststatus_id, created_at, comment) VALUES
    (7, 2004, NULL, 1, now() - interval '4 days', NULL);

INSERT INTO consecutive_ministries (id, request_id, consecutive_manifest, consecutive_remesa) VALUES
    (10, 2004, 30001, 40001),   -- fila real
    (11, 2004, NULL,  NULL);    -- fila huerfana posterior (id mayor)
