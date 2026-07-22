-- Subconjunto MINIMO de tablas del origen necesarias para consulta-base.sql.
-- Solo las columnas efectivamente referenciadas en el SELECT/JOIN/WHERE.
-- Tipos ALINEADOS a lo verificado contra Fletx real (docs/verificacion-tipos-destino-v4.md
-- y docs/Estructura_tablas_fletx.sql de Controlt): standby=integer, freight=bigint,
-- document/referencephone*=bigint, trailers.empty_weight=varchar.
-- Sin FK declaradas (igual que Controlt): los fixtures no necesitan integridad
-- referencial, solo IDs consistentes entre tablas.
SET search_path TO test_origen_mf;

-- ─── Catálogos simples ───
CREATE TABLE requeststatuses      (id bigint PRIMARY KEY, name text, description text);
CREATE TABLE transport_companies  (id bigint PRIMARY KEY, name text);
CREATE TABLE cities               (id bigint PRIMARY KEY, name text);
CREATE TABLE departments          (id bigint PRIMARY KEY, name text);
CREATE TABLE businesstypes        (id bigint PRIMARY KEY, name text);
CREATE TABLE bigcustomers         (id bigint PRIMARY KEY, name text);
CREATE TABLE booking_types        (id bigint PRIMARY KEY, name text);
CREATE TABLE sider_types          (id bigint PRIMARY KEY, name text);
CREATE TABLE distribution_types   (id bigint PRIMARY KEY, name text);
CREATE TABLE cartypes             (id bigint PRIMARY KEY, name text);
CREATE TABLE carmarks             (id bigint PRIMARY KEY, name text);
CREATE TABLE carlines             (id bigint PRIMARY KEY, value text);
CREATE TABLE carcolors            (id bigint PRIMARY KEY, name text);
CREATE TABLE trailermarks         (id bigint PRIMARY KEY, name text);
CREATE TABLE doctypes             (id bigint PRIMARY KEY, name text);
CREATE TABLE productcodes         (id bigint PRIMARY KEY, value text);

-- ─── Rutas ───
CREATE TABLE routes         (id bigint PRIMARY KEY, from_city_id bigint, to_city_id bigint);
CREATE TABLE pathroutes     (id bigint PRIMARY KEY, name text, route_id bigint);
CREATE TABLE businessroutes (id bigint PRIMARY KEY, pathroute_id bigint);

-- ─── Negocio ───
CREATE TABLE carconfigs (id bigint PRIMARY KEY, description text, capacity integer, maximum_weight integer);
CREATE TABLE businesses  (id bigint PRIMARY KEY, description text, businesstype_id bigint);

-- ─── Generadores de carga (cliente / cliente_paga) ───
CREATE TABLE loadgenerators (
    id            bigint PRIMARY KEY,
    doctype_id    bigint,
    document      bigint,        -- ⚠️ bigint, no varchar (verificacion-tipos-destino-v4.md)
    checkdigit    integer,
    name          text,
    lastname      text,
    address       text,
    phone         text,
    mobile        text,
    city_id       bigint,
    department_id bigint
);

-- ─── Direcciones (plantas) ───
CREATE TABLE addresses (id bigint PRIMARY KEY, name text);

-- ─── Booking ───
CREATE TABLE bookings (
    id                    bigint PRIMARY KEY,
    bigcustomer_id        bigint,
    business_id           bigint,
    businessroute_id      bigint,
    carconfig_id          bigint,
    cartype_id            bigint,
    sider_type_id         bigint,
    booking_type_id       bigint,
    distribution_type_id  bigint,
    paga_id               bigint,
    date                  timestamp,
    created_at            timestamp,
    freight_driver        numeric,
    freight_company       numeric
);

CREATE TABLE booking_addresses (
    id              bigint PRIMARY KEY,
    booking_id      bigint,
    address_id      bigint,
    load_address    boolean DEFAULT false,
    unload_address  boolean DEFAULT false
);

CREATE TABLE businessproducts (
    id              bigint PRIMARY KEY,
    business_id     bigint,
    description     text,
    productcode_id  bigint,
    status          boolean
);

-- ─── Personas (conductor / propietarios / tenedores) ───
CREATE TABLE people (
    id                          bigint PRIMARY KEY,
    document                    bigint,   -- ⚠️ bigint, no varchar
    checkdigit                  integer,
    name                        text,
    secondname                  text,
    lastname                    text,
    lastname2                   text,
    address                     text,
    phone                       text,
    email                       text,
    emergency_contact_name      text,
    emergency_contact_phone     text,
    emergency_contact_address   text,
    expedition_date             date,
    due_date                    date,
    referencephone1             bigint,   -- ⚠️ bigint, no varchar
    referencephone2             bigint,   -- ⚠️ bigint, no varchar
    referencephone3             bigint,   -- ⚠️ bigint, no varchar
    doctype_id                  bigint,
    department_id               bigint,
    city_id                     bigint
);

-- ─── Vehículo / trailer ───
CREATE TABLE vehicles (
    id            bigint PRIMARY KEY,
    placa         varchar(10),
    carmark_id    bigint,
    carline_id    bigint,
    carcolor_id   bigint,
    cartype_id    bigint,
    is_owner      boolean,
    is_affiliate  integer DEFAULT 0,
    owner_id      bigint,
    holder_id     bigint
);

CREATE TABLE trailers (
    id              bigint PRIMARY KEY,
    placa           varchar(10),
    cartype_id      bigint,
    trailermark_id  bigint,
    carcolor_id     bigint,
    empty_weight    varchar,    -- ⚠️ varchar en Fletx, no numérico
    model           integer,
    num_axes        integer,
    owner_id        bigint,
    holder_id       bigint
);

-- ─── Requests (grano de la consulta) ───
CREATE TABLE requests (
    id                     bigint PRIMARY KEY,
    created_at             timestamp NOT NULL,
    updated_at             timestamp,
    booking_id             bigint,
    requeststatus_id       bigint,
    driver_id              bigint,
    vehicle_id             bigint,
    trailer_id             bigint,
    loadgenerator_id       bigint,
    transport_company_id   bigint,
    name_receives          text,
    phone_receives         text,
    another_receives       boolean DEFAULT false,
    loading_date           timestamp,
    freight                bigint,    -- ⚠️ bigint, no numeric
    value_pay_to_driver    numeric(10,2),
    load_weight            integer,
    load_units             integer,
    ingresoid_remesa       text,
    ingresoid_manifest     text,
    seguridadqr_manifest   text,
    sinister               boolean,
    standby                integer,   -- ⚠️ integer, no boolean
    exclusive_fleet        boolean,
    seconds_to_destiny     bigint,
    has_auction            boolean DEFAULT true,
    load_id                bigint,
    trip_id                bigint,
    operation_type         text
);

CREATE TABLE events (
    id                      bigint PRIMARY KEY,
    request_id              bigint,
    requeststatatusold_id   bigint,
    requeststatus_id        bigint,
    created_at              timestamp NOT NULL,
    comment                 text
);

CREATE TABLE consecutive_ministries (
    id                    bigint PRIMARY KEY,
    request_id            bigint,
    consecutive_manifest  bigint,
    consecutive_remesa    bigint
);

CREATE TABLE comply_destinations (
    id            bigint PRIMARY KEY,
    request_id    bigint,
    accomplished  boolean,
    annulled      boolean,
    date_comply   timestamp
);

CREATE TABLE liquidations (
    id                          bigint PRIMARY KEY,
    request_id                  bigint,
    consecutive                 bigint,
    advance                     bigint,
    balance                     bigint,
    deductions                  bigint,
    agreed_value_to_pay_driver  bigint,
    was_paid                    boolean,
    legalized                   boolean,
    date_annulled               timestamp
);

-- Índices equivalentes a los esperados en origen (para que el plan en test se parezca)
CREATE INDEX ix_requests_created_at            ON requests (created_at);
CREATE INDEX ix_events_request_created         ON events (request_id, created_at);
CREATE INDEX ix_consecutive_ministries_request ON consecutive_ministries (request_id);
