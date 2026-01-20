-- Baseline migration: Complete database schema as of consolidation
-- This replaces all previous migrations (V0.1 through V79)
-- Previous migrations archived in flyway_old/

--
-- PostgreSQL database dump
--




--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


--
-- Name: document_part_type; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public.document_part_type AS ENUM (
    'vendor_contact',
    'ship_to_contact',
    'line_item'
);


--
-- Name: contacts_search_vector_update(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contacts_search_vector_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.search_vector := 
    setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(NEW.email, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(NEW.phone, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(NEW.notes, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.address->>'address', '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.address->>'city', '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.address->>'state_or_province', '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.address->>'country', '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.address->>'postal_code', '')), 'C');
  RETURN NEW;
END
$$;


--
-- Name: decrement_attachments_count(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.decrement_attachments_count() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE email_message SET attachments_count = attachments_count - 1
    WHERE id = OLD.message_id;
    RETURN OLD;
END;
$$;


--
-- Name: gen_ulid(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.gen_ulid() RETURNS text
    LANGUAGE plpgsql
    AS $$
DECLARE
  -- Crockford's Base32
  encoding   BYTEA = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  timestamp  BYTEA = E'\\000\\000\\000\\000\\000\\000';
  output     TEXT = '';

  unix_time  BIGINT;
  ulid       BYTEA;
BEGIN
  -- 6 timestamp bytes
  unix_time = (EXTRACT(EPOCH FROM CLOCK_TIMESTAMP()) * 1000)::BIGINT;
  timestamp = SET_BYTE(timestamp, 0, (unix_time >> 40)::BIT(8)::INTEGER);
  timestamp = SET_BYTE(timestamp, 1, (unix_time >> 32)::BIT(8)::INTEGER);
  timestamp = SET_BYTE(timestamp, 2, (unix_time >> 24)::BIT(8)::INTEGER);
  timestamp = SET_BYTE(timestamp, 3, (unix_time >> 16)::BIT(8)::INTEGER);
  timestamp = SET_BYTE(timestamp, 4, (unix_time >> 8)::BIT(8)::INTEGER);
  timestamp = SET_BYTE(timestamp, 5, unix_time::BIT(8)::INTEGER);

  -- 10 entropy bytes
  ulid = timestamp || gen_random_bytes(10);

  -- Encode the timestamp
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 0) & 224) >> 5));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 0) & 31)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 1) & 248) >> 3));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 1) & 7) << 2) | ((GET_BYTE(ulid, 2) & 192) >> 6)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 2) & 62) >> 1));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 2) & 1) << 4) | ((GET_BYTE(ulid, 3) & 240) >> 4)));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 3) & 15) << 1) | ((GET_BYTE(ulid, 4) & 128) >> 7)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 4) & 124) >> 2));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 4) & 3) << 3) | ((GET_BYTE(ulid, 5) & 224) >> 5)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 5) & 31)));

  -- Encode the entropy
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 6) & 248) >> 3));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 6) & 7) << 2) | ((GET_BYTE(ulid, 7) & 192) >> 6)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 7) & 62) >> 1));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 7) & 1) << 4) | ((GET_BYTE(ulid, 8) & 240) >> 4)));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 8) & 15) << 1) | ((GET_BYTE(ulid, 9) & 128) >> 7)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 9) & 124) >> 2));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 9) & 3) << 3) | ((GET_BYTE(ulid, 10) & 224) >> 5)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 10) & 31)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 11) & 248) >> 3));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 11) & 7) << 2) | ((GET_BYTE(ulid, 12) & 192) >> 6)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 12) & 62) >> 1));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 12) & 1) << 4) | ((GET_BYTE(ulid, 13) & 240) >> 4)));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 13) & 15) << 1) | ((GET_BYTE(ulid, 14) & 128) >> 7)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 14) & 124) >> 2));
  output = output || CHR(GET_BYTE(encoding, ((GET_BYTE(ulid, 14) & 3) << 3) | ((GET_BYTE(ulid, 15) & 224) >> 5)));
  output = output || CHR(GET_BYTE(encoding, (GET_BYTE(ulid, 15) & 31)));

  RETURN output;
END
$$;


--
-- Name: increment_attachments_count(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.increment_attachments_count() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE email_message SET attachments_count = attachments_count + 1
    WHERE id = NEW.message_id;
    RETURN NEW;
END;
$$;


--
-- Name: properties_search_vector_update(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.properties_search_vector_update() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW.search_vector :=
    setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(NEW.address, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(NEW.city, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(NEW.state_or_province, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.country, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C') ||
    setweight(to_tsvector('english', COALESCE(
      (SELECT string_agg(key, ' ') FROM jsonb_object_keys(NEW.amenities) AS key), 
      ''
    )), 'D');
  RETURN NEW;
END;
$$;


--
-- Name: set_po_display_id(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_po_display_id() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    project_key_val TEXT;
    next_val INT;
    attempts INT := 0;
    max_attempts CONSTANT INT := 10;
BEGIN
    -- Only generate a display_id if one is not already provided on insert.
    IF NEW.display_id IS NULL THEN
        -- Get the project key (e.g. "ACME")
        SELECT key INTO project_key_val FROM projects WHERE id = NEW.project_id;

        -- Retry-safe loop to handle insert/update race
        WHILE attempts < max_attempts LOOP
            attempts := attempts + 1;

            -- Try to increment the counter atomically
            UPDATE project_po_counters
            SET last_value = last_value + 1
            WHERE project_id = NEW.project_id
            RETURNING last_value INTO next_val;

            -- If successful update, break loop
            IF FOUND THEN
                EXIT;
            END IF;

            -- If not found, attempt insert
            BEGIN
                INSERT INTO project_po_counters (project_id, last_value)
                VALUES (NEW.project_id, 1);
                next_val := 1;
                EXIT;
            EXCEPTION WHEN unique_violation THEN
                -- Another session inserted first — try again
                PERFORM pg_sleep(0.01); -- 10ms delay
            END;
        END LOOP;

        IF attempts = max_attempts THEN
            RAISE EXCEPTION 'Could not generate display_id for project % after % attempts', NEW.project_id, max_attempts;
        END IF;

        -- Set the display ID using the project key
        NEW.display_id := project_key_val || '-' || next_val;
    END IF;

    RETURN NEW;
END;
$$;


--
-- Name: sync_purchase_order_contact_names(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.sync_purchase_order_contact_names() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Update vendor_name for all purchase orders using this contact as vendor
    -- Only update purchase orders belonging to the same company for security
    UPDATE purchase_orders
    SET vendor_name = NEW.name,
        updated_at = NOW()
    WHERE vendor_contact_id = NEW.id
      AND company_id = NEW.company_id;
    
    -- Update ship_to_name for all purchase orders using this contact as ship-to
    -- Only update purchase orders belonging to the same company for security
    UPDATE purchase_orders
    SET ship_to_name = NEW.name,
        updated_at = NOW()
    WHERE ship_to_contact_id = NEW.id
      AND company_id = NEW.company_id;
    
    RETURN NEW;
END;
$$;


--
-- Name: update_delivery_slips_search_vector(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_delivery_slips_search_vector() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', 
        COALESCE(NEW.document_number, '') || ' ' || 
        COALESCE(NEW.po_number, '') || ' ' || 
        COALESCE(NEW.project_name, '') || ' ' ||
        COALESCE(NEW.job_number, '') || ' ' ||
        COALESCE(NEW.delivery_method_note, '')
    );
    RETURN NEW;
END;
$$;


--
-- Name: update_email_message_search_vector(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_email_message_search_vector() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', 
        COALESCE(NEW.from_address, '') || ' ' || 
        COALESCE(NEW.to_address, '') || ' ' || 
        COALESCE(NEW.cc, '') || ' ' || 
        COALESCE(NEW.bcc, '') || ' ' || 
        COALESCE(NEW.subject, '') || ' ' || 
        COALESCE(NEW.body_text, '')
    );
    RETURN NEW;
END;
$$;


--
-- Name: update_invoices_search_vector(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_invoices_search_vector() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', 
        COALESCE(NEW.document_number, '') || ' ' || 
        COALESCE(NEW.po_number, '') || ' ' || 
        COALESCE(NEW.project_name, '') || ' ' ||
        COALESCE(NEW.order_ticket_number, '')
    );
    RETURN NEW;
END;
$$;


--
-- Name: update_projects_search_vector(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_projects_search_vector() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', 
        COALESCE(NEW.name, '') || ' ' || 
        COALESCE(NEW.key, '') || ' ' || 
        COALESCE(NEW.description, '')
    );
    RETURN NEW;
END;
$$;


--
-- Name: update_purchase_order_items_count(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_purchase_order_items_count() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE purchase_orders
        SET items_count = items_count + 1
        WHERE id = NEW.purchase_order_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE purchase_orders
        SET items_count = items_count - 1
        WHERE id = OLD.purchase_order_id;
        RETURN OLD;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Handle case where purchase_order_id changes (rare but possible)
        IF OLD.purchase_order_id <> NEW.purchase_order_id THEN
            UPDATE purchase_orders
            SET items_count = items_count - 1
            WHERE id = OLD.purchase_order_id;
            
            UPDATE purchase_orders
            SET items_count = items_count + 1
            WHERE id = NEW.purchase_order_id;
        END IF;
        RETURN NEW;
    END IF;
END;
$$;


--
-- Name: update_purchase_orders_search_vector(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.update_purchase_orders_search_vector() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', 
        COALESCE(NEW.display_id, '') || ' ' || 
        COALESCE(NEW.notes, '') || ' ' ||
        COALESCE(NEW.vendor_contact->>'name', '') || ' ' ||
        COALESCE(NEW.ship_to_contact->>'name', '')
    );
    RETURN NEW;
END;
$$;



--
-- Name: approved_senders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.approved_senders (
    id character varying DEFAULT public.gen_ulid() NOT NULL,
    company_id bigint NOT NULL,
    status character varying(50) DEFAULT 'pending'::character varying NOT NULL,
    approved_by character varying,
    approved_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    sender_identifier character varying NOT NULL,
    whitelist_type character varying(10) NOT NULL,
    scheduled_deletion_at timestamp with time zone
);


--
-- Name: TABLE approved_senders; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.approved_senders IS 'Stores approved email senders (exact email or domain patterns) for spam prevention';


--
-- Name: COLUMN approved_senders.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.approved_senders.status IS 'Approval status: pending, approved, rejected';


--
-- Name: COLUMN approved_senders.approved_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.approved_senders.approved_by IS 'User ID who approved/rejected this sender (null for pending)';


--
-- Name: COLUMN approved_senders.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.approved_senders.updated_at IS 'Timestamp when the approved sender was last updated';


--
-- Name: COLUMN approved_senders.sender_identifier; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.approved_senders.sender_identifier IS 'Email address or domain (e.g., user@example.com or example.com)';


--
-- Name: COLUMN approved_senders.whitelist_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.approved_senders.whitelist_type IS 'Type of whitelist: email (exact match) or domain (all emails from domain)';


--
-- Name: COLUMN approved_senders.scheduled_deletion_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.approved_senders.scheduled_deletion_at IS 'Timestamp when all threads from this sender should be deleted (set when sender is rejected)';


--
-- Name: companies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.companies (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    description text,
    logo_url text,
    terms_url text,
    privacy_url text,
    assigned_email character varying(255),
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone
);


--
-- Name: COLUMN companies.assigned_email; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.companies.assigned_email IS 'Email assigned by tosspaper to this company';


--
-- Name: companies_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.companies_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: companies_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.companies_id_seq OWNED BY public.companies.id;


--
-- Name: contacts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contacts (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    address jsonb,
    notes text,
    phone character varying(50),
    email character varying(255),
    status character varying(50) NOT NULL,
    tag character varying(255),
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    search_vector tsvector,
    CONSTRAINT contacts_email_phone_check CHECK ((((tag)::text = 'ship_to'::text) OR (phone IS NOT NULL) OR (email IS NOT NULL)))
);


--
-- Name: COLUMN contacts.address; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.contacts.address IS 'Structured address as JSON object with fields: address, country, state_or_province, city, postal_code';


--
-- Name: COLUMN contacts.search_vector; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.contacts.search_vector IS 'Full-text search vector for name, email, phone, notes, and address fields';


--
-- Name: CONSTRAINT contacts_email_phone_check ON contacts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON CONSTRAINT contacts_email_phone_check ON public.contacts IS 'Ship-to contacts can be created without email or phone; all other contacts require at least one.';


--
-- Name: delivery_notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_notes (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    extraction_task_id character varying(255) NOT NULL,
    company_id bigint NOT NULL,
    document_number character varying(255),
    document_date date,
    project_id character varying(255),
    project_name character varying(255),
    job_number character varying(255),
    po_number character varying(255),
    seller_info jsonb,
    buyer_info jsonb,
    ship_to_info jsonb,
    bill_to_info jsonb,
    line_items jsonb,
    delivery_method_note character varying(255),
    shipment_details jsonb,
    delivery_acknowledgement jsonb,
    purchase_order_id character varying(255),
    status character varying(50) DEFAULT 'draft'::character varying,
    created_at timestamp with time zone DEFAULT now(),
    created_by character varying(255),
    updated_at timestamp with time zone
);


--
-- Name: TABLE delivery_notes; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.delivery_notes IS 'Delivery notes (formal proof of delivery). All shipment and logistics data now stored in shipment_details and delivery_acknowledgement JSONB objects.';


--
-- Name: COLUMN delivery_notes.seller_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_notes.seller_info IS 'Seller/supplier party information (standardized)';


--
-- Name: COLUMN delivery_notes.buyer_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_notes.buyer_info IS 'Buyer party information (standardized)';


--
-- Name: COLUMN delivery_notes.ship_to_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_notes.ship_to_info IS 'Ship to party information (standardized)';


--
-- Name: COLUMN delivery_notes.bill_to_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_notes.bill_to_info IS 'Bill to party information (optional)';


--
-- Name: COLUMN delivery_notes.line_items; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_notes.line_items IS 'Line items JSONB - ALWAYS includes ticket_number, price, weight when available in source document';


--
-- Name: delivery_slips; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_slips (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    extraction_task_id character varying(255) NOT NULL,
    company_id bigint NOT NULL,
    document_number character varying(255),
    document_date date,
    project_id character varying(255),
    project_name character varying(255),
    job_number character varying(255),
    po_number character varying(255),
    delivery_method_note character varying(255),
    seller_info jsonb,
    buyer_info jsonb,
    ship_to_info jsonb,
    line_items jsonb,
    shipment_details jsonb,
    delivery_acknowledgement jsonb,
    created_at timestamp with time zone DEFAULT now(),
    created_by character varying(255),
    search_vector tsvector,
    purchase_order_id character varying(255),
    status character varying(50) DEFAULT 'draft'::character varying,
    updated_at timestamp with time zone,
    bill_to_info jsonb
);


--
-- Name: TABLE delivery_slips; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.delivery_slips IS 'Structured delivery slip records. All shipment and delivery acknowledgement data stored in JSONB objects.';


--
-- Name: COLUMN delivery_slips.document_number; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.document_number IS 'Delivery ticket number from conformed JSON';


--
-- Name: COLUMN delivery_slips.document_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.document_date IS 'Delivery date from conformed JSON';


--
-- Name: COLUMN delivery_slips.seller_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.seller_info IS 'Seller/supplier party information (standardized name)';


--
-- Name: COLUMN delivery_slips.buyer_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.buyer_info IS 'Buyer party information (standardized name)';


--
-- Name: COLUMN delivery_slips.ship_to_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.ship_to_info IS 'Ship to party information (standardized name)';


--
-- Name: COLUMN delivery_slips.line_items; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.line_items IS 'Line items JSONB - always includes ticket_number, price, weight when available';


--
-- Name: COLUMN delivery_slips.purchase_order_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.purchase_order_id IS 'ID of the matched purchase order (if any)';


--
-- Name: COLUMN delivery_slips.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.status IS 'Document status: DRAFT, APPROVED, REJECTED';


--
-- Name: COLUMN delivery_slips.bill_to_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.delivery_slips.bill_to_info IS 'Bill to party information (optional)';


--
-- Name: document_approvals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_approvals (
    id character varying(26) DEFAULT public.gen_ulid() NOT NULL,
    assigned_id character varying(255) NOT NULL,
    company_id bigint NOT NULL,
    from_email character varying(255) NOT NULL,
    document_type character varying(50) NOT NULL,
    project_id character varying(255),
    approved_at timestamp with time zone,
    rejected_at timestamp with time zone,
    reviewed_by character varying(100),
    review_notes text,
    document_summary text,
    storage_key character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    external_document_number text,
    po_number character varying(255),
    CONSTRAINT chk_document_approvals_status CHECK (((approved_at IS NULL) OR (rejected_at IS NULL) OR (approved_at >= rejected_at)))
);


--
-- Name: TABLE document_approvals; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_approvals IS 'Tracks document approval workflow - document_id removed as it was unused';


--
-- Name: COLUMN document_approvals.id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_approvals.id IS 'ULID primary key for cursor-based pagination';


--
-- Name: COLUMN document_approvals.assigned_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_approvals.assigned_id IS 'Reference to extraction_task.assigned_id (unique)';


--
-- Name: COLUMN document_approvals.approved_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_approvals.approved_at IS 'Timestamp when document was approved (NULL = not approved)';


--
-- Name: COLUMN document_approvals.rejected_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_approvals.rejected_at IS 'Timestamp when document was rejected (NULL = not rejected). Can be set even if approved_at is set (rejected then approved)';


--
-- Name: COLUMN document_approvals.document_summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_approvals.document_summary IS 'Description of what the document contains';


--
-- Name: document_part_comparisons; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_part_comparisons (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    extraction_id character varying(255) NOT NULL,
    part_type public.document_part_type NOT NULL,
    extracted_item_index integer,
    extracted_part_name character varying(500),
    extracted_part_description text,
    matched_po_id character varying(255),
    matched_part_type public.document_part_type,
    matched_item_index integer,
    matched_part_name character varying(500),
    match_score numeric(5,4),
    confidence numeric(5,4),
    match_reasons text,
    discrepancy_details jsonb,
    created_at timestamp with time zone DEFAULT now()
);


--
-- Name: TABLE document_part_comparisons; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_part_comparisons IS 'Stores comparison results between extracted document parts (vendor, ship-to, line items) and stored PO parts';


--
-- Name: COLUMN document_part_comparisons.part_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_part_comparisons.part_type IS 'Type of document part: vendor_contact, ship_to_contact, or line_item';


--
-- Name: COLUMN document_part_comparisons.extracted_item_index; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_part_comparisons.extracted_item_index IS 'Index for line items (0-based), NULL for contacts';


--
-- Name: COLUMN document_part_comparisons.match_score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_part_comparisons.match_score IS 'NULL indicates no match found, non-NULL indicates a match with confidence score';


--
-- Name: COLUMN document_part_comparisons.discrepancy_details; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.document_part_comparisons.discrepancy_details IS 'AI-generated JSON with all discrepancies (quantity, price, description, etc.)';


--
-- Name: email_attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_attachment (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    message_id uuid NOT NULL,
    assigned_id character varying(255) NOT NULL,
    file_name text NOT NULL,
    content_type character varying(255) NOT NULL,
    size_bytes bigint NOT NULL,
    storage_url text NOT NULL,
    local_file_path text NOT NULL,
    checksum character varying(64) NOT NULL,
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    attempts integer DEFAULT 0,
    region character varying(50),
    endpoint text,
    metadata jsonb,
    last_updated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now()
);


--
-- Name: TABLE email_attachment; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.email_attachment IS 'Stores metadata for message attachments';


--
-- Name: COLUMN email_attachment.assigned_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.assigned_id IS 'Assigned unique identifier (providerMessageId_uuid)';


--
-- Name: COLUMN email_attachment.storage_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.storage_url IS 'Location where the attachment file is stored';


--
-- Name: COLUMN email_attachment.local_file_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.local_file_path IS 'Local file path where the file is stored in the filesystem';


--
-- Name: COLUMN email_attachment.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.status IS 'Upload status: pending, processing, uploaded, failed';


--
-- Name: COLUMN email_attachment.attempts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.attempts IS 'Number of upload attempts made';


--
-- Name: COLUMN email_attachment.region; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.region IS 'AWS region where the file is stored (e.g., us-east-1, eu-west-1). NULL for local filesystem storage.';


--
-- Name: COLUMN email_attachment.endpoint; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.endpoint IS 'AWS endpoint where the file is stored (e.g., s3.amazonaws.com, s3.us-east-1.amazonaws.com). NULL for local filesystem storage.';


--
-- Name: COLUMN email_attachment.metadata; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.metadata IS 'Additional metadata for the attachment (S3 tags, custom fields, etc.) stored as JSON';


--
-- Name: COLUMN email_attachment.last_updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_attachment.last_updated_at IS 'Timestamp when the attachment was last updated';


--
-- Name: email_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_message (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    thread_id uuid NOT NULL,
    provider character varying(50) NOT NULL,
    provider_message_id character varying(255) NOT NULL,
    in_reply_to character varying(255),
    from_address text NOT NULL,
    to_address text NOT NULL,
    cc text,
    bcc text,
    body_text text,
    body_html text,
    headers jsonb,
    direction character varying(10) NOT NULL,
    status character varying(20) DEFAULT 'received'::character varying NOT NULL,
    provider_timestamp timestamp with time zone,
    created_at timestamp with time zone DEFAULT now(),
    attachments_count integer DEFAULT 0,
    subject text,
    search_vector tsvector,
    company_id bigint NOT NULL,
    CONSTRAINT email_message_direction_check CHECK (((direction)::text = ANY ((ARRAY['incoming'::character varying, 'outgoing'::character varying])::text[])))
);


--
-- Name: TABLE email_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.email_message IS 'Each individual email, inbound or outbound';


--
-- Name: COLUMN email_message.provider_message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_message.provider_message_id IS 'Unique message ID per provider';


--
-- Name: COLUMN email_message.attachments_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_message.attachments_count IS 'Denormalized count of attachments for fast queries';


--
-- Name: COLUMN email_message.subject; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_message.subject IS 'Email subject line for search and display';


--
-- Name: email_thread; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_thread (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    subject text,
    provider character varying(50) NOT NULL,
    provider_thread_id character varying(255),
    message_count integer DEFAULT 0,
    last_updated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now(),
    deleted_at timestamp with time zone
);


--
-- Name: TABLE email_thread; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.email_thread IS 'Top-level conversation record for grouping related messages';


--
-- Name: COLUMN email_thread.provider_thread_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_thread.provider_thread_id IS 'External thread identifier for multi-provider correlation';


--
-- Name: COLUMN email_thread.deleted_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.email_thread.deleted_at IS 'Timestamp when this thread was soft deleted (NULL = active). Messages and attachments are filtered via JOIN.';


--
-- Name: extraction_embeddings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.extraction_embeddings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    content text NOT NULL,
    metadata jsonb,
    embedding public.vector(1536),
    created_at timestamp with time zone DEFAULT now()
);


--
-- Name: TABLE extraction_embeddings; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.extraction_embeddings IS 'Vector embeddings for extraction content to enable RAG';


--
-- Name: COLUMN extraction_embeddings.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_embeddings.content IS 'The extracted text content to be embedded';


--
-- Name: COLUMN extraction_embeddings.metadata; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_embeddings.metadata IS 'Additional metadata stored as JSON';


--
-- Name: COLUMN extraction_embeddings.embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_embeddings.embedding IS 'Vector embedding for similarity search';


--
-- Name: COLUMN extraction_embeddings.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_embeddings.created_at IS 'When the embedding was created';


--
-- Name: extraction_task; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.extraction_task (
    assigned_id character varying(255) NOT NULL,
    storage_key character varying(500) NOT NULL,
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    extraction_started_at timestamp with time zone,
    preparation_id character varying(255),
    task_id character varying(255),
    error_message text,
    attempts integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    preparation_started_at timestamp with time zone,
    extract_task_results jsonb,
    from_address text,
    to_address text,
    document_type character varying(50),
    conformed_json jsonb,
    conformance_score numeric(3,2),
    conformance_status character varying(20),
    conformance_attempts integer DEFAULT 0,
    conformance_history jsonb,
    conformance_evaluation jsonb,
    conformed_at timestamp with time zone,
    match_type text,
    review_status text,
    project_id text,
    purchase_order_id text,
    email_message_id uuid NOT NULL,
    email_thread_id uuid NOT NULL,
    email_subject text,
    received_at timestamp with time zone,
    company_id bigint NOT NULL,
    po_number character varying(255),
    match_report jsonb,
    CONSTRAINT extraction_task_latest_match_type_check CHECK ((match_type = ANY (ARRAY['pending'::text, 'in_progress'::text, 'direct'::text, 'ai_match'::text, 'no_match'::text, 'manual'::text, 'no_po_required'::text])))
);


--
-- Name: TABLE extraction_task; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.extraction_task IS 'Tracks AI extraction tasks for email attachments. Extraction content stored in vector database.';


--
-- Name: COLUMN extraction_task.assigned_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.assigned_id IS 'Primary key - reference to the email attachment assigned ID';


--
-- Name: COLUMN extraction_task.storage_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.storage_key IS 'Storage key for the file to be processed';


--
-- Name: COLUMN extraction_task.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.status IS 'Current status: pending, processing, succeeded, failed, cancelled';


--
-- Name: COLUMN extraction_task.extraction_started_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.extraction_started_at IS 'When the extraction task was started';


--
-- Name: COLUMN extraction_task.preparation_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.preparation_id IS 'Provider-specific ID from file preparation step (e.g., Chunkr file ID)';


--
-- Name: COLUMN extraction_task.task_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.task_id IS 'Provider-specific task ID for the extraction job';


--
-- Name: COLUMN extraction_task.error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.error_message IS 'Error message encountered during processing';


--
-- Name: COLUMN extraction_task.attempts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.attempts IS 'Number of execution attempts for this task';


--
-- Name: COLUMN extraction_task.created_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.created_at IS 'Timestamp when the task was created';


--
-- Name: COLUMN extraction_task.updated_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.updated_at IS 'Timestamp when the task was last updated';


--
-- Name: COLUMN extraction_task.preparation_started_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.preparation_started_at IS 'Timestamp when the preparation started';


--
-- Name: COLUMN extraction_task.extract_task_results; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.extract_task_results IS 'Full extract task response from Chunkr AI including output, citations, metrics, and metadata stored as JSONB';


--
-- Name: COLUMN extraction_task.document_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.document_type IS 'Classified document type: PURCHASE_ORDER, INVOICE, DELIVERY_SLIP, UNKNOWN';


--
-- Name: COLUMN extraction_task.conformed_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformed_json IS 'AI-conformed JSON matching schema with quality validation';


--
-- Name: COLUMN extraction_task.conformance_score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformance_score IS 'Quality score from AI evaluation (0.0 to 1.0) - denormalized for fast queries';


--
-- Name: COLUMN extraction_task.conformance_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformance_status IS 'Conformance status: PENDING, PROCESSING, VALIDATED, NEEDS_REVIEW, FAILED';


--
-- Name: COLUMN extraction_task.conformance_attempts; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformance_attempts IS 'Number of conformance retry attempts';


--
-- Name: COLUMN extraction_task.conformance_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformance_history IS 'Array of attempt summaries: [{attempt: 1, score: 0.72, issues: [...]}, ...]';


--
-- Name: COLUMN extraction_task.conformance_evaluation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformance_evaluation IS 'Full final EvaluationResponse from AI including score, issues, correctedJson, and suggestions for review';


--
-- Name: COLUMN extraction_task.conformed_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.conformed_at IS 'Timestamp when conformance completed';


--
-- Name: COLUMN extraction_task.match_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.extraction_task.match_type IS 'Latest match status: pending (not yet matched), in_progress (AI actively searching), direct (matched by PO number), ai_match (AI determined match), no_match (no PO found), manual (manually linked by user), no_po_required (user confirmed no PO needed)';
--
-- Name: invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoices (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    extraction_task_id character varying(255) NOT NULL,
    company_id bigint NOT NULL,
    document_number character varying(255),
    document_date date,
    project_id character varying(255),
    project_name character varying(255),
    po_number character varying(255),
    order_ticket_number character varying(255),
    seller_info jsonb,
    line_items jsonb,
    received_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now(),
    created_by character varying(255),
    status character varying(50) DEFAULT 'pending'::character varying NOT NULL,
    search_vector tsvector,
    purchase_order_id character varying(255),
    updated_at timestamp with time zone,
    buyer_info jsonb,
    ship_to_info jsonb,
    bill_to_info jsonb,
    invoice_details jsonb
);


--
-- Name: TABLE invoices; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.invoices IS 'Structured invoice records created from approved conformed JSON. Financial details (currency, payment terms, due date) now stored in invoice_details JSONB.';


--
-- Name: COLUMN invoices.document_number; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.document_number IS 'Invoice number from conformed JSON';


--
-- Name: COLUMN invoices.document_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.document_date IS 'Invoice date from conformed JSON';


--
-- Name: COLUMN invoices.seller_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.seller_info IS 'Vendor/seller party information (standardized name)';


--
-- Name: COLUMN invoices.line_items; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.line_items IS 'Line items JSONB - always includes ticket_number, price, weight when available';


--
-- Name: COLUMN invoices.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.status IS 'Invoice processing status (created after document approval): pending, paid, rejected, cancelled. overdue is calculated from dueDate in application layer.';


--
-- Name: COLUMN invoices.purchase_order_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.purchase_order_id IS 'ID of the matched purchase order (if any)';


--
-- Name: COLUMN invoices.buyer_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.buyer_info IS 'Buyer party information (optional for invoices)';


--
-- Name: COLUMN invoices.ship_to_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.ship_to_info IS 'Ship to party information (optional)';


--
-- Name: COLUMN invoices.bill_to_info; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.bill_to_info IS 'Bill to party information (optional)';


--
-- Name: COLUMN invoices.invoice_details; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.invoices.invoice_details IS 'Invoice-specific financial information including currencyCode and paymentTerms';


--
-- Name: project_po_counters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_po_counters (
    project_id character varying(255) NOT NULL,
    last_value integer DEFAULT 0 NOT NULL
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255) NOT NULL,
    key character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now(),
    status character varying(50) NOT NULL,
    description text,
    updated_at timestamp with time zone,
    search_vector tsvector,
    default_shipping_location character varying(255)
);


--
-- Name: COLUMN projects.default_shipping_location; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.projects.default_shipping_location IS 'Default shipping/delivery contact for this project. References contacts.id';


--
-- Name: properties; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.properties (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    company_id bigint NOT NULL,
    name character varying(255),
    address character varying(255) NOT NULL,
    country character varying(100) NOT NULL,
    state_or_province character varying(100) NOT NULL,
    city character varying(100) NOT NULL,
    postal_code character varying(20) NOT NULL,
    description text,
    status character varying(20) NOT NULL,
    search_vector tsvector,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    amenities jsonb DEFAULT '{}'::jsonb
);


--
-- Name: purchase_order_document_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_document_items (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    purchase_order_document_id character varying(255) NOT NULL,
    item_name character varying(255) NOT NULL,
    quantity integer DEFAULT 0 NOT NULL,
    unit_price numeric(10,2) NOT NULL,
    total_price numeric(12,2),
    notes text,
    metadata jsonb DEFAULT '{}'::jsonb
);


--
-- Name: purchase_order_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_documents (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    purchase_order_id character varying(255) NOT NULL,
    status character varying(50) DEFAULT 'pending_approval'::character varying NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    notes text
);


--
-- Name: purchase_order_document_flat_items; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.purchase_order_document_flat_items AS
 SELECT pod.id AS document_id,
    pod.purchase_order_id,
    pod.status AS document_status,
    pod.metadata AS document_metadata,
    pod.created_at AS document_created_at,
    pod.updated_at AS document_updated_at,
    podi.id AS item_id,
    podi.item_name,
    podi.quantity,
    podi.unit_price,
    podi.total_price,
    podi.notes,
    podi.metadata AS item_metadata
   FROM (public.purchase_order_documents pod
     JOIN public.purchase_order_document_items podi ON (((pod.id)::text = (podi.purchase_order_document_id)::text)));


--
-- Name: purchase_order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_items (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    purchase_order_id character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    quantity integer DEFAULT 0 NOT NULL,
    unit_price numeric(10,2),
    expected_delivery_date date,
    delivery_status character varying(50) DEFAULT 'pending'::character varying,
    notes text,
    metadata jsonb DEFAULT '{}'::jsonb,
    unit character varying(50),
    unit_code character varying(255)
);


--
-- Name: purchase_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_orders (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    display_id text,
    project_id character varying(255) NOT NULL,
    company_id bigint NOT NULL,
    order_date date,
    due_date date,
    status character varying(50) NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone,
    change_log jsonb DEFAULT '[]'::jsonb,
    notes text,
    items_count integer DEFAULT 0 NOT NULL,
    search_vector tsvector,
    vendor_contact jsonb,
    ship_to_contact jsonb
);


--
-- Name: COLUMN purchase_orders.items_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.purchase_orders.items_count IS 'Count of items in this purchase order, maintained by triggers';


--
-- Name: purchase_order_flat_items; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.purchase_order_flat_items AS
 SELECT po.id AS purchase_order_id,
    po.display_id,
    po.project_id,
    po.vendor_contact,
    po.ship_to_contact,
    po.order_date,
    po.due_date,
    po.status,
    po.metadata AS po_metadata,
    po.created_at,
    po.updated_at,
    po.change_log,
    po.company_id,
    po.notes,
    poi.id AS item_id,
    poi.name,
    poi.quantity,
    poi.unit_price,
    poi.expected_delivery_date,
    poi.delivery_status,
    poi.notes AS item_notes,
    poi.metadata AS item_metadata,
    poi.unit,
    poi.unit_code
   FROM (public.purchase_orders po
     LEFT JOIN public.purchase_order_items poi ON (((po.id)::text = (poi.purchase_order_id)::text)));


--
-- Name: units; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.units (
    id character varying(255) DEFAULT public.gen_ulid() NOT NULL,
    property_id character varying(255) NOT NULL,
    unit_number character varying(50) NOT NULL,
    floor_number integer,
    unit_class character varying(50) NOT NULL,
    name character varying(255),
    description text,
    bedrooms integer,
    bathrooms numeric(3,1),
    square_feet integer,
    base_rate numeric(10,2),
    rate_period character varying(20) NOT NULL,
    charges jsonb DEFAULT '{}'::jsonb,
    status character varying(50) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    amenities jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    building_section character varying(50),
    view_type character varying(100),
    max_occupancy integer,
    pet_policy jsonb,
    accessibility_features jsonb,
    lease_terms jsonb
);


--
-- Name: companies id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies ALTER COLUMN id SET DEFAULT nextval('public.companies_id_seq'::regclass);


--
-- Name: approved_senders approved_senders_company_id_sender_identifier_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approved_senders
    ADD CONSTRAINT approved_senders_company_id_sender_identifier_key UNIQUE (company_id, sender_identifier);


--
-- Name: approved_senders approved_senders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approved_senders
    ADD CONSTRAINT approved_senders_pkey PRIMARY KEY (id);


--
-- Name: companies companies_assigned_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_assigned_email_key UNIQUE (assigned_email);


--
-- Name: companies companies_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_email_key UNIQUE (email);


--
-- Name: companies companies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);


--
-- Name: contacts contacts_company_id_email_tag_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_company_id_email_tag_key UNIQUE (company_id, email, tag);


--
-- Name: contacts contacts_company_id_phone_tag_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_company_id_phone_tag_key UNIQUE (company_id, phone, tag);


--
-- Name: contacts contacts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_pkey PRIMARY KEY (id);


--
-- Name: delivery_notes delivery_notes_extraction_task_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_notes
    ADD CONSTRAINT delivery_notes_extraction_task_id_key UNIQUE (extraction_task_id);


--
-- Name: delivery_notes delivery_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_notes
    ADD CONSTRAINT delivery_notes_pkey PRIMARY KEY (id);


--
-- Name: delivery_slips delivery_slips_extraction_task_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_slips
    ADD CONSTRAINT delivery_slips_extraction_task_id_key UNIQUE (extraction_task_id);


--
-- Name: delivery_slips delivery_slips_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_slips
    ADD CONSTRAINT delivery_slips_pkey PRIMARY KEY (id);


--
-- Name: document_approvals document_approvals_assigned_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_assigned_id_key UNIQUE (assigned_id);


--
-- Name: document_approvals document_approvals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_pkey PRIMARY KEY (id);


--
-- Name: document_part_comparisons document_part_comparisons_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_part_comparisons
    ADD CONSTRAINT document_part_comparisons_pkey PRIMARY KEY (id);


--
-- Name: email_attachment email_attachment_assigned_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_attachment
    ADD CONSTRAINT email_attachment_assigned_id_key UNIQUE (assigned_id);


--
-- Name: email_attachment email_attachment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_attachment
    ADD CONSTRAINT email_attachment_pkey PRIMARY KEY (id);


--
-- Name: email_message email_message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_message
    ADD CONSTRAINT email_message_pkey PRIMARY KEY (id);


--
-- Name: email_message email_message_provider_provider_message_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_message
    ADD CONSTRAINT email_message_provider_provider_message_id_key UNIQUE (provider, provider_message_id);


--
-- Name: email_thread email_thread_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_thread
    ADD CONSTRAINT email_thread_pkey PRIMARY KEY (id);


--
-- Name: email_thread email_thread_provider_provider_thread_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_thread
    ADD CONSTRAINT email_thread_provider_provider_thread_id_key UNIQUE (provider, provider_thread_id);


--
-- Name: extraction_embeddings extraction_embeddings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.extraction_embeddings
    ADD CONSTRAINT extraction_embeddings_pkey PRIMARY KEY (id);


--
-- Name: extraction_task extraction_task_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.extraction_task
    ADD CONSTRAINT extraction_task_pkey PRIMARY KEY (assigned_id);


--
--



--
-- Name: invoices invoices_extraction_task_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_extraction_task_id_key UNIQUE (extraction_task_id);


--
-- Name: invoices invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);


--
-- Name: project_po_counters project_po_counters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_po_counters
    ADD CONSTRAINT project_po_counters_pkey PRIMARY KEY (project_id);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: properties properties_company_id_address_country_state_or_province_cit_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.properties
    ADD CONSTRAINT properties_company_id_address_country_state_or_province_cit_key UNIQUE (company_id, address, country, state_or_province, city, postal_code);


--
-- Name: properties properties_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.properties
    ADD CONSTRAINT properties_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_document_items purchase_order_document_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_document_items
    ADD CONSTRAINT purchase_order_document_items_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_documents purchase_order_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_documents
    ADD CONSTRAINT purchase_order_documents_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_items purchase_order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_pkey PRIMARY KEY (id);


--
-- Name: purchase_orders purchase_orders_company_id_display_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_company_id_display_id_key UNIQUE (company_id, display_id);


--
-- Name: purchase_orders purchase_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_pkey PRIMARY KEY (id);


--
-- Name: projects unique_key_per_company; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT unique_key_per_company UNIQUE (company_id, key);


--
-- Name: units units_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.units
    ADD CONSTRAINT units_pkey PRIMARY KEY (id);


--
-- Name: units units_property_id_unit_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.units
    ADD CONSTRAINT units_property_id_unit_number_key UNIQUE (property_id, unit_number);


--
-- Name: extraction_embeddings_embedding_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX extraction_embeddings_embedding_idx ON public.extraction_embeddings USING hnsw (embedding public.vector_cosine_ops);


--
--



--
-- Name: idx_approved_senders_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approved_senders_lookup ON public.approved_senders USING btree (sender_identifier, company_id, status);


--
-- Name: idx_approved_senders_scheduled_deletion; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_approved_senders_scheduled_deletion ON public.approved_senders USING btree (scheduled_deletion_at) WHERE (scheduled_deletion_at IS NOT NULL);


--
-- Name: idx_companies_assigned_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_companies_assigned_email ON public.companies USING btree (assigned_email) WHERE (assigned_email IS NOT NULL);


--
-- Name: idx_contacts_search_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_contacts_search_vector ON public.contacts USING gin (search_vector);


--
-- Name: idx_delivery_notes_company; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_notes_company ON public.delivery_notes USING btree (company_id);


--
-- Name: idx_delivery_notes_extraction_task; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_delivery_notes_extraction_task ON public.delivery_notes USING btree (extraction_task_id);


--
-- Name: idx_delivery_notes_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_notes_po ON public.delivery_notes USING btree (purchase_order_id) WHERE (purchase_order_id IS NOT NULL);


--
-- Name: idx_delivery_notes_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_notes_status ON public.delivery_notes USING btree (status);


--
-- Name: idx_delivery_slips_search_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_delivery_slips_search_vector ON public.delivery_slips USING gin (search_vector);


--
-- Name: idx_doc_id_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_id_item_id ON public.purchase_order_document_items USING btree (purchase_order_document_id, id);


--
-- Name: idx_doc_part_comp_extraction; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_part_comp_extraction ON public.document_part_comparisons USING btree (extraction_id);


--
-- Name: idx_doc_part_comp_part_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_part_comp_part_type ON public.document_part_comparisons USING btree (extraction_id, part_type);


--
-- Name: idx_doc_part_comp_po; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_part_comp_po ON public.document_part_comparisons USING btree (matched_po_id);


--
-- Name: idx_doc_part_comp_unmatched; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_doc_part_comp_unmatched ON public.document_part_comparisons USING btree (extraction_id, match_score) WHERE (match_score IS NULL);


--
-- Name: idx_document_approvals_company_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_approvals_company_id ON public.document_approvals USING btree (company_id, id);


--
-- Name: idx_document_approvals_company_project_approved; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_approvals_company_project_approved ON public.document_approvals USING btree (company_id, project_id, approved_at) WHERE ((project_id IS NOT NULL) AND (approved_at IS NOT NULL));


--
-- Name: idx_document_approvals_company_project_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_approvals_company_project_pending ON public.document_approvals USING btree (company_id, project_id) WHERE ((project_id IS NOT NULL) AND (approved_at IS NULL) AND (rejected_at IS NULL));


--
-- Name: idx_document_approvals_company_project_rejected; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_document_approvals_company_project_rejected ON public.document_approvals USING btree (company_id, project_id, rejected_at) WHERE ((project_id IS NOT NULL) AND (rejected_at IS NOT NULL) AND (approved_at IS NULL));


--
-- Name: idx_email_message_company_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_message_company_id ON public.email_message USING btree (company_id);


--
-- Name: idx_email_message_search_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_message_search_vector ON public.email_message USING gin (search_vector);


--
-- Name: idx_email_thread_deleted_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_email_thread_deleted_at ON public.email_thread USING btree (deleted_at) WHERE (deleted_at IS NULL);


--
-- Name: idx_extraction_task_company_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_extraction_task_company_id ON public.extraction_task USING btree (company_id);


--
-- Name: idx_extraction_task_conformance_score; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_extraction_task_conformance_score ON public.extraction_task USING btree (conformance_score) WHERE (conformance_score IS NOT NULL);


--
-- Name: idx_extraction_task_conformance_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_extraction_task_conformance_status ON public.extraction_task USING btree (conformance_status) WHERE (conformance_status IS NOT NULL);


--
-- Name: idx_invoices_search_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_invoices_search_vector ON public.invoices USING gin (search_vector);


--
-- Name: idx_pod_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pod_id ON public.purchase_order_documents USING btree (id);


--
-- Name: idx_podi_doc_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_podi_doc_id ON public.purchase_order_document_items USING btree (purchase_order_document_id);


--
-- Name: idx_projects_search_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_projects_search_vector ON public.projects USING gin (search_vector);


--
-- Name: idx_purchase_order_items_po_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_items_po_id ON public.purchase_order_items USING btree (purchase_order_id);


--
-- Name: idx_purchase_order_items_po_id_item_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_order_items_po_id_item_id ON public.purchase_order_items USING btree (purchase_order_id, id);


--
-- Name: idx_purchase_orders_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_purchase_orders_id ON public.purchase_orders USING btree (id);


--
-- Name: idx_purchase_orders_order_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_order_date ON public.purchase_orders USING btree (order_date);


--
-- Name: idx_purchase_orders_project_display_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_purchase_orders_project_display_id ON public.purchase_orders USING btree (project_id, display_id);


--
-- Name: idx_purchase_orders_search_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_search_vector ON public.purchase_orders USING gin (search_vector);


--
-- Name: idx_purchase_orders_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_purchase_orders_status ON public.purchase_orders USING btree (status);


--
-- Name: properties_search_vector_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX properties_search_vector_idx ON public.properties USING gin (search_vector);


--
-- Name: contacts contacts_search_vector_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER contacts_search_vector_trigger BEFORE INSERT OR UPDATE ON public.contacts FOR EACH ROW EXECUTE FUNCTION public.contacts_search_vector_update();


--
-- Name: delivery_slips delivery_slips_search_vector_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER delivery_slips_search_vector_trigger BEFORE INSERT OR UPDATE ON public.delivery_slips FOR EACH ROW EXECUTE FUNCTION public.update_delivery_slips_search_vector();


--
-- Name: email_message email_message_search_vector_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER email_message_search_vector_trigger BEFORE INSERT OR UPDATE ON public.email_message FOR EACH ROW EXECUTE FUNCTION public.update_email_message_search_vector();


--
-- Name: invoices invoices_search_vector_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER invoices_search_vector_trigger BEFORE INSERT OR UPDATE ON public.invoices FOR EACH ROW EXECUTE FUNCTION public.update_invoices_search_vector();


--
-- Name: projects projects_search_vector_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER projects_search_vector_trigger BEFORE INSERT OR UPDATE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.update_projects_search_vector();


--
-- Name: properties properties_search_vector_update_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER properties_search_vector_update_trigger BEFORE INSERT OR UPDATE ON public.properties FOR EACH ROW EXECUTE FUNCTION public.properties_search_vector_update();


--
-- Name: purchase_orders purchase_orders_search_vector_trigger; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER purchase_orders_search_vector_trigger BEFORE INSERT OR UPDATE ON public.purchase_orders FOR EACH ROW EXECUTE FUNCTION public.update_purchase_orders_search_vector();


--
-- Name: email_attachment trg_decrement_attachments_count; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_decrement_attachments_count AFTER DELETE ON public.email_attachment FOR EACH ROW EXECUTE FUNCTION public.decrement_attachments_count();


--
-- Name: email_attachment trg_increment_attachments_count; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_increment_attachments_count AFTER INSERT ON public.email_attachment FOR EACH ROW EXECUTE FUNCTION public.increment_attachments_count();


--
-- Name: purchase_orders trigger_set_po_display_id; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trigger_set_po_display_id BEFORE INSERT ON public.purchase_orders FOR EACH ROW EXECUTE FUNCTION public.set_po_display_id();


--
-- Name: contacts trigger_sync_purchase_order_contact_names; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trigger_sync_purchase_order_contact_names AFTER UPDATE OF name ON public.contacts FOR EACH ROW WHEN (((old.name)::text IS DISTINCT FROM (new.name)::text)) EXECUTE FUNCTION public.sync_purchase_order_contact_names();


--
-- Name: purchase_order_items trigger_update_po_items_count_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trigger_update_po_items_count_delete AFTER DELETE ON public.purchase_order_items FOR EACH ROW EXECUTE FUNCTION public.update_purchase_order_items_count();


--
-- Name: purchase_order_items trigger_update_po_items_count_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trigger_update_po_items_count_insert AFTER INSERT ON public.purchase_order_items FOR EACH ROW EXECUTE FUNCTION public.update_purchase_order_items_count();


--
-- Name: purchase_order_items trigger_update_po_items_count_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trigger_update_po_items_count_update AFTER UPDATE OF purchase_order_id ON public.purchase_order_items FOR EACH ROW EXECUTE FUNCTION public.update_purchase_order_items_count();


--
-- Name: approved_senders approved_senders_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.approved_senders
    ADD CONSTRAINT approved_senders_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: contacts contacts_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contacts
    ADD CONSTRAINT contacts_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: document_approvals document_approvals_assigned_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_assigned_id_fkey FOREIGN KEY (assigned_id) REFERENCES public.extraction_task(assigned_id);


--
-- Name: document_approvals document_approvals_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_approvals
    ADD CONSTRAINT document_approvals_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: email_attachment email_attachment_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_attachment
    ADD CONSTRAINT email_attachment_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.email_message(id) ON DELETE CASCADE;


--
-- Name: email_message email_message_thread_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_message
    ADD CONSTRAINT email_message_thread_id_fkey FOREIGN KEY (thread_id) REFERENCES public.email_thread(id) ON DELETE CASCADE;


--
-- Name: delivery_notes fk_delivery_notes_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_notes
    ADD CONSTRAINT fk_delivery_notes_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: delivery_notes fk_delivery_notes_extraction_task; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_notes
    ADD CONSTRAINT fk_delivery_notes_extraction_task FOREIGN KEY (extraction_task_id) REFERENCES public.extraction_task(assigned_id);


--
-- Name: delivery_slips fk_delivery_slips_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_slips
    ADD CONSTRAINT fk_delivery_slips_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: delivery_slips fk_delivery_slips_extraction_task; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_slips
    ADD CONSTRAINT fk_delivery_slips_extraction_task FOREIGN KEY (extraction_task_id) REFERENCES public.extraction_task(assigned_id);


--
-- Name: email_message fk_email_message_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_message
    ADD CONSTRAINT fk_email_message_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: document_part_comparisons fk_extraction; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_part_comparisons
    ADD CONSTRAINT fk_extraction FOREIGN KEY (extraction_id) REFERENCES public.extraction_task(assigned_id) ON DELETE CASCADE;


--
-- Name: extraction_task fk_extraction_task_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.extraction_task
    ADD CONSTRAINT fk_extraction_task_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: invoices fk_invoices_company; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT fk_invoices_company FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: invoices fk_invoices_extraction_task; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT fk_invoices_extraction_task FOREIGN KEY (extraction_task_id) REFERENCES public.extraction_task(assigned_id);


--
-- Name: projects fk_projects_default_shipping_location; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT fk_projects_default_shipping_location FOREIGN KEY (default_shipping_location) REFERENCES public.contacts(id);


--
-- Name: projects projects_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: properties properties_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.properties
    ADD CONSTRAINT properties_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: purchase_order_document_items purchase_order_document_items_purchase_order_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_document_items
    ADD CONSTRAINT purchase_order_document_items_purchase_order_document_id_fkey FOREIGN KEY (purchase_order_document_id) REFERENCES public.purchase_order_documents(id) ON DELETE CASCADE;


--
-- Name: purchase_order_documents purchase_order_documents_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_documents
    ADD CONSTRAINT purchase_order_documents_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON DELETE CASCADE;


--
-- Name: purchase_order_items purchase_order_items_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON DELETE CASCADE;


--
-- Name: purchase_orders purchase_orders_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id);


--
-- Name: purchase_orders purchase_orders_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: units units_property_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.units
    ADD CONSTRAINT units_property_id_fkey FOREIGN KEY (property_id) REFERENCES public.properties(id);


--
-- PostgreSQL database dump complete
--


