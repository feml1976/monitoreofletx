-- Fixtures ESTATICAS para los tests de mapeo: catalogos, booking, personas,
-- vehiculo/trailer. Ejecutado una sola vez al crear el contenedor (initdb).
-- Ninguna columna de fecha aqui depende de la ventana de sincronizacion
-- (esa es la unica cosa filtrada por now(); ver fixtures/origen-requests-dinamicos.sql).
SET search_path TO test_origen_mf;

INSERT INTO requeststatuses (id, name, description) VALUES
    (1,  'Creado',        'Solicitud creada'),
    (7,  'Fin de Cargue',  'Carga finalizada'),
    (10, 'En transito',    'Vehiculo en ruta'),
    (15, 'Anulado',        'Solicitud anulada');

INSERT INTO transport_companies (id, name) VALUES (1, 'Transer S.A.');

INSERT INTO cities (id, name) VALUES (1, 'Bogota'), (2, 'Medellin'), (3, 'Cali');
INSERT INTO departments (id, name) VALUES (1, 'Cundinamarca'), (2, 'Antioquia');

INSERT INTO routes (id, from_city_id, to_city_id) VALUES (1, 1, 2);
INSERT INTO pathroutes (id, name, route_id) VALUES (1, 'BOG-MED', 1);
INSERT INTO businessroutes (id, pathroute_id) VALUES (1, 1);

INSERT INTO businesstypes (id, name) VALUES (1, 'Carga masiva');
INSERT INTO businesses (id, description, businesstype_id) VALUES (1, 'Cemento Andino', 1);
INSERT INTO bigcustomers (id, name) VALUES (1, 'Cliente Demo S.A.');

INSERT INTO booking_types (id, name) VALUES (1, 'Sencillo'), (2, 'Redondo');
INSERT INTO sider_types (id, name) VALUES (1, 'Sider'), (2, 'Sin Sider');
INSERT INTO distribution_types (id, name) VALUES (1, 'Directa');

INSERT INTO carconfigs (id, description, capacity, maximum_weight) VALUES (1, 'Tractocamion', 34000, 34000);
INSERT INTO cartypes (id, name) VALUES (1, 'Planchon'), (2, 'Furgon');
INSERT INTO carmarks (id, name) VALUES (1, 'Kenworth');
INSERT INTO carlines (id, value) VALUES (1, 'T800');
INSERT INTO carcolors (id, name) VALUES (1, 'Blanco'), (2, 'Azul');
INSERT INTO trailermarks (id, name) VALUES (1, 'Refricargo');
INSERT INTO doctypes (id, name) VALUES (1, 'Cedula de ciudadania'), (2, 'NIT');

-- id=1: generador de carga del request (rq.loadgenerator_id). id=2: cliente_paga (bk.paga_id).
-- document/checkdigit numericos, sin ceros a la izquierda (consistente con Fletx real).
INSERT INTO loadgenerators (id, doctype_id, document, checkdigit, name, lastname, address, phone, mobile, city_id, department_id) VALUES
    (1, 2, 900123456, 5, 'Cliente Demo', 'S.A.',  'Calle 10 # 20-30', '6011234567', '3001112222', 1, 1),
    (2, 2, 800654321, 3, 'Pagador',      'Corp.', 'Cra 5 # 1-1',      '6017654321', '3002223333', 1, 1);

INSERT INTO addresses (id, name) VALUES
    (201, 'Planta Fusagasuga'),
    (202, 'Bodega Cali'),
    (203, 'Planta Girardot'),
    (204, 'Planta Facatativa'),
    (205, 'Bodega Unica');

-- Booking 3001: origen/destino 1:1 (para request 2001, happy path).
-- Booking 3003: multi-recogida (2 direcciones de carga + 1 de descarga).
INSERT INTO bookings (id, bigcustomer_id, business_id, businessroute_id, carconfig_id, cartype_id,
                       sider_type_id, booking_type_id, distribution_type_id, paga_id,
                       date, created_at, freight_driver, freight_company) VALUES
    (3001, 1, 1, 1, 1, 1, 1, 2, 1, 2, now(), now(), 150000.50, 200000.75),
    (3003, 1, 1, 1, 1, 1, 1, 2, 1, 2, now(), now(), 150000.50, 200000.75);

INSERT INTO booking_addresses (id, booking_id, address_id, load_address, unload_address) VALUES
    (1, 3001, 201, true,  false),
    (2, 3001, 202, false, true),
    (3, 3003, 203, true,  false),
    (4, 3003, 204, true,  false),
    (5, 3003, 205, false, true);

INSERT INTO productcodes (id, value) VALUES (1, 'COD-001');
INSERT INTO businessproducts (id, business_id, description, productcode_id, status) VALUES
    (1, 1, 'Cemento', 1, true);

-- Personas: 5 roles distintos (conductor, propietario/tenedor de vehiculo,
-- propietario/tenedor de trailer) para detectar mezclas entre los 5 alias de
-- "people" en el JOIN (pp_dv, ppow_vh, pphd_vh, ppow_tr, pphd_tr).
INSERT INTO people (id, document, checkdigit, name, secondname, lastname, lastname2,
                     address, phone, email, emergency_contact_name, emergency_contact_phone,
                     emergency_contact_address, expedition_date, due_date,
                     referencephone1, referencephone2, referencephone3,
                     doctype_id, department_id, city_id) VALUES
    (401, 1020304050, 7, 'Juan',  'Carlos', 'Perez',  'Gomez', 'Calle 1 # 2-3', '6011111111',
        'juan.perez@example.com', 'Maria Perez', '3009998888', 'Calle 9 # 8-7',
        '2015-03-10', '2030-03-10', 3001234567, 3007654321, 3009876543, 1, 1, 1),
    (402, 500111222, 1, 'Ana',    NULL, 'Owner',  NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (403, 500333444, 2, 'Hilda',  NULL, 'Holder', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (404, 500555666, 3, 'Oscar',  NULL, 'DuenoTr',NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (405, 500777888, 4, 'Rosa',   NULL, 'TeneTr', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO vehicles (id, placa, carmark_id, carline_id, carcolor_id, cartype_id, is_owner, is_affiliate, owner_id, holder_id) VALUES
    (501, 'ABC123', 1, 1, 1, 1, true, 0, 402, 403);

-- empty_weight NO numerico a proposito (⚠️ hallazgo peso_vacio): prueba que el
-- mapeo lo trata como texto, no como numero.
INSERT INTO trailers (id, placa, cartype_id, trailermark_id, carcolor_id, empty_weight, model, num_axes, owner_id, holder_id) VALUES
    (601, 'R99999', 2, 1, 2, '2.5 Ton', 2020, 3, 404, 405);
