package ar.edu.utn.dds.k3003;

import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.CategoriaDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.DonacionDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.EstadoDonacionEnum;
import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.IdentificadorDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.ProductoDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.donadoresYEntidades.DonadorDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.donadoresYEntidades.QuejaDTO;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonaciones;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonadoresYEntidades;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaLogistica;
import ar.edu.utn.dds.k3003.exceptions.DonacionRechazadaException;
import ar.edu.utn.dds.k3003.exceptions.IntegracionException;
import ar.edu.utn.dds.k3003.exceptions.RecursoEnUsoException;
import ar.edu.utn.dds.k3003.model.CambioEstadoDonacion;
import ar.edu.utn.dds.k3003.model.Categoria;
import ar.edu.utn.dds.k3003.model.Donacion;
import ar.edu.utn.dds.k3003.model.EstadoDonacion;
import ar.edu.utn.dds.k3003.model.Identificador;
import ar.edu.utn.dds.k3003.model.Producto;
import ar.edu.utn.dds.k3003.repositories.CategoriaMapper;
import ar.edu.utn.dds.k3003.repositories.CategoriasRepository;
import ar.edu.utn.dds.k3003.repositories.DonacionesMapper;
import ar.edu.utn.dds.k3003.repositories.DonacionesRepository;
import ar.edu.utn.dds.k3003.repositories.GeneradorDeIds;
import ar.edu.utn.dds.k3003.repositories.IdentificadorMapper;
import ar.edu.utn.dds.k3003.repositories.IdentificadoresRepository;
import ar.edu.utn.dds.k3003.repositories.InMemoryCategoriasRepo;
import ar.edu.utn.dds.k3003.repositories.InMemoryDonacionesRepo;
import ar.edu.utn.dds.k3003.repositories.InMemoryGeneradorDeIds;
import ar.edu.utn.dds.k3003.repositories.InMemoryIdentificadoresRepo;
import ar.edu.utn.dds.k3003.repositories.InMemoryProductosRepo;
import ar.edu.utn.dds.k3003.repositories.ProductoMapper;
import ar.edu.utn.dds.k3003.repositories.ProductosRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ar.edu.utn.dds.k3003.messaging.ReadModelEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

@Service
@Transactional
public class Fachada implements FachadaDonaciones {

  private FachadaDonadoresYEntidades fachadaDonadoresYEntidades;
  private FachadaLogistica fachadaLogistica;
  private final DonacionesRepository donacionesRepository;
  private final ProductosRepository productosRepository;
  private final IdentificadoresRepository identificadoresRepository;
  private final CategoriasRepository categoriasRepository;
  private final GeneradorDeIds generadorDeIds;
  private final DonacionesMapper donacionesMapper = new DonacionesMapper();
  private final ProductoMapper productoMapper = new ProductoMapper();
  private final IdentificadorMapper identificadorMapper = new IdentificadorMapper();
  private final CategoriaMapper categoriaMapper = new CategoriaMapper();
  private final MeterRegistry meterRegistry;

  @Autowired(required = false)
  private ReadModelEventPublisher readModelEventPublisher;

  /** Constructor sin argumentos: es el que usa el test base de la catedra. */
  public Fachada() {
    GeneradorDeIds generador = new InMemoryGeneradorDeIds();
    this.generadorDeIds = generador;
    this.donacionesRepository = new InMemoryDonacionesRepo(generador);
    this.productosRepository = new InMemoryProductosRepo(generador);
    this.identificadoresRepository = new InMemoryIdentificadoresRepo(generador);
    this.categoriasRepository = new InMemoryCategoriasRepo(generador);
    this.meterRegistry = null;
  }

  @Autowired
  public Fachada(
      DonacionesRepository donacionesRepository,
      ProductosRepository productosRepository,
      IdentificadoresRepository identificadoresRepository,
      CategoriasRepository categoriasRepository,
      GeneradorDeIds generadorDeIds,
      MeterRegistry meterRegistry) {
    this.donacionesRepository = donacionesRepository;
    this.productosRepository = productosRepository;
    this.identificadoresRepository = identificadoresRepository;
    this.categoriasRepository = categoriasRepository;
    this.generadorDeIds = generadorDeIds;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void setFachadaDonadoresYEntidades(FachadaDonadoresYEntidades fachada) {
    this.fachadaDonadoresYEntidades = fachada;
  }

  @Override
  public void setFachadaLogistica(FachadaLogistica fachada) {
    this.fachadaLogistica = fachada;
  }

  // ---------------------------------------------------------------- metricas

  private void incrementarMetrica(String nombre) {
    if (meterRegistry != null) {
      Counter.builder(nombre).register(meterRegistry).increment();
    }
  }

  /** Mide latencia ademas de contar: un contador solo no dice si el vecino esta lento. */
  private <T> T medirIntegracion(String componente, String operacion, Callable<T> llamada) {
    long inicio = System.nanoTime();
    try {
      T resultado = llamada.call();
      registrarTiempo(componente, operacion, "ok", inicio);
      return resultado;
    } catch (HttpClientErrorException.NotFound e) {
      registrarTiempo(componente, operacion, "not_found", inicio);
      throw e;
    } catch (Exception e) {
      registrarTiempo(componente, operacion, "error", inicio);
      incrementarMetrica("donatrack.donaciones.integracion." + componente + ".errores");
      throw new IntegracionException(
          componente, "Fallo la comunicacion con " + componente + ": " + e.getMessage(), e);
    }
  }

  private void registrarTiempo(String componente, String operacion, String resultado, long inicio) {
    if (meterRegistry != null) {
      Timer.builder("donatrack.donaciones.integracion.duracion")
          .tag("componente", componente)
          .tag("operacion", operacion)
          .tag("resultado", resultado)
          .register(meterRegistry)
          .record(System.nanoTime() - inicio, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  // ------------------------------------------------------------- donaciones

  @Override
  public DonacionDTO registrarDonacion(DonacionDTO donacionDTO) {
    if (donacionDTO == null) {
      throw new IllegalArgumentException("La donacion no puede ser nula");
    }
    if (donacionDTO.cantidad() == null || donacionDTO.cantidad() <= 0) {
      throw new IllegalArgumentException("La cantidad donada debe ser mayor a cero");
    }
    if (donacionDTO.id() != null && !donacionDTO.id().isBlank()) {
      throw new IllegalArgumentException("El ID de la donacion lo genera el modulo");
    }
    if (productosRepository.findById(donacionDTO.productoID()).isEmpty()) {
      throw new NoSuchElementException("El producto indicado no existe");
    }

    validarQueElDonadorPuedaDonar(donacionDTO.donadorID());

    Donacion donacion =
        new Donacion(
            donacionDTO.donadorID(),
            donacionDTO.depositoID(),
            donacionDTO.descripcion(),
            donacionDTO.productoID(),
            donacionDTO.cantidad());
    donacion.setId(generadorDeIds.siguiente("donacion"));

    Donacion guardada = donacionesRepository.save(donacion);

    if (fachadaLogistica != null) {
      medirIntegracion(
          "logistica",
          "gestionarDonacion",
          () ->
              fachadaLogistica.gestionarDonacion(
                  guardada.getDepositoId(),
                  guardada.getId(),
                  guardada.getProductoId(),
                  guardada.getCantidad()));
    }

    publicarEvento("donacion", guardada.getId(), "UPSERT", snapshotDonacion(guardada));
    ar.edu.utn.dds.k3003.observability.DomainEvents.info(org.slf4j.LoggerFactory.getLogger(Fachada.class), "donacion.registrada donacion={} deposito={} producto={} cantidad={}", guardada.getId(), guardada.getDepositoId(), guardada.getProductoId(), guardada.getCantidad());
    incrementarMetrica("donatrack.donaciones.registradas");
    return donacionesMapper.toDTO(guardada);
  }

  /**
   * Recepcion y rechazo: el donador tiene que existir en Donadores y Entidades y estar habilitado.
   * Un donador sospechoso solo puede donar la mitad de las veces, por lo que un rechazo aca es un
   * resultado esperable del negocio y no un error del cliente.
   */
  private void validarQueElDonadorPuedaDonar(String donadorID) {
    if (fachadaDonadoresYEntidades == null) {
      throw new IntegracionException(
          "donadores", "No hay integracion configurada con Donadores y Entidades", null);
    }

    DonadorDTO donador;
    try {
      donador =
          medirIntegracion(
              "donadores",
              "buscarDonadorPorID",
              () -> fachadaDonadoresYEntidades.buscarDonadorPorID(donadorID));
    } catch (HttpClientErrorException.NotFound | NoSuchElementException e) {
      throw new DonacionRechazadaException(
          "El donador " + donadorID + " no esta registrado: la donacion no puede procesarse");
    }
    if (donador == null) {
      throw new DonacionRechazadaException(
          "El donador " + donadorID + " no esta registrado: la donacion no puede procesarse");
    }

    Boolean puedeDonar =
        medirIntegracion(
            "donadores", "puedeDonar", () -> fachadaDonadoresYEntidades.puedeDonar(donadorID));

    if (!Boolean.TRUE.equals(puedeDonar)) {
      incrementarMetrica("donatrack.donaciones.rechazadas");
      throw new DonacionRechazadaException(
          "El donador " + donadorID + " no se encuentra habilitado para donar");
    }
  }

  @Override
  public DonacionDTO buscarDonacionPorID(String donacionID) throws NoSuchElementException {
    return donacionesMapper.toDTO(buscarDonacion(donacionID));
  }

  private Donacion buscarDonacion(String donacionID) {
    return donacionesRepository
        .findById(donacionID)
        .orElseThrow(() -> new NoSuchElementException("No existe una donacion con ese ID"));
  }

  public List<DonacionDTO> listarDonaciones() {
    return ordenarCronologicamente(donacionesRepository.findAll()).stream()
        .map(donacionesMapper::toDTO)
        .collect(Collectors.toList());
  }

  /** Trazabilidad: expone el historial completo sin necesidad de mirar la base de datos. */
  public List<CambioEstadoDonacion> obtenerHistorialDeEstados(String donacionID) {
    return buscarDonacion(donacionID).getHistorialEstados();
  }

  @Override
  public DonacionDTO cambiarEstadoDeDonacion(String donacionID, EstadoDonacionEnum estado)
      throws NoSuchElementException {
    Donacion donacion = buscarDonacion(donacionID);

    if (donacion.getEstadoActual() == donacionesMapper.toModel(estado)) return donacionesMapper.toDTO(donacion);
    donacion.cambiarEstado(
        donacionesMapper.toModel(estado), "Cambio de estado reportado por Logistica/Sistema");
    donacionesRepository.save(donacion);
    publicarEvento("donacion", donacion.getId(), "UPSERT", snapshotDonacion(donacion));

    if (EstadoDonacionEnum.ACEPTADA.equals(estado)) {
      incrementarMetrica("donatrack.donaciones.aceptadas");
      if (meterRegistry != null) io.micrometer.core.instrument.Timer.builder("donatrack.donaciones.ingreso.aceptacion")
          .description("Desde ingreso hasta primera aceptación; puede existir más de un paquete por donación")
          .publishPercentileHistogram().register(meterRegistry).record(
              java.time.Duration.between(donacion.getFechaIngresoExacta(), java.time.LocalDateTime.now()));
    }

    return donacionesMapper.toDTO(donacion);
  }

  @Override
  public List<DonacionDTO> buscarPorDonadorYFechaInicio(String donadorID, LocalDate fecha)
      throws NoSuchElementException {
    List<Donacion> donacionesDelDonador = donacionesRepository.buscarPorDonador(donadorID);

    // Sin donaciones no se puede distinguir "el donador no dono nunca" de "el donador no existe",
    // asi que se confirma contra Donadores y Entidades antes de devolver una lista vacia.
    if (donacionesDelDonador.isEmpty() && fachadaDonadoresYEntidades != null) {
      DonadorDTO donador =
          medirIntegracion(
              "donadores",
              "buscarDonadorPorID",
              () -> fachadaDonadoresYEntidades.buscarDonadorPorID(donadorID));
      if (donador == null) {
        throw new NoSuchElementException("El donador " + donadorID + " no existe");
      }
      return List.of();
    }

    return ordenarCronologicamente(donacionesDelDonador).stream()
        .filter(d -> fecha == null || !d.getFechaIngreso().isBefore(fecha))
        .map(donacionesMapper::toDTO)
        .collect(Collectors.toList());
  }

  private List<Donacion> ordenarCronologicamente(List<Donacion> donaciones) {
    return donaciones.stream()
        .sorted(
            Comparator.comparing(Donacion::getFechaIngresoExacta)
                .thenComparing(Donacion::getId, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  @Override
  public DonacionDTO registrarQuejaEnDonacion(String donacionID, String descripcion) {
    if (donacionID == null) {
      throw new IllegalArgumentException("El ID de la donacion no puede ser nulo");
    }

    Donacion donacion = buscarDonacion(donacionID);

    if (!EstadoDonacion.ACEPTADA.equals(donacion.getEstadoActual())) {
      throw new IllegalArgumentException(
          "Solo se puede registrar una queja sobre una donacion aceptada");
    }

    QuejaDTO quejaDTO =
        new QuejaDTO(null, donacion.getId(), donacion.getDonadorId(), LocalDate.now(), descripcion);
    medirIntegracion(
        "donadores", "agregarQueja", () -> fachadaDonadoresYEntidades.agregarQueja(quejaDTO));

    donacion.cambiarEstado(EstadoDonacion.CONQUEJA, descripcion);
    donacionesRepository.save(donacion);
    publicarEvento("donacion", donacion.getId(), "UPSERT", snapshotDonacion(donacion));

    ar.edu.utn.dds.k3003.observability.DomainEvents.info(org.slf4j.LoggerFactory.getLogger(Fachada.class), "queja.registrada donacion={} estado=CONQUEJA", donacion.getId());
    incrementarMetrica("donatrack.donaciones.quejas.registradas");

    return donacionesMapper.toDTO(donacion);
  }

  // -------------------------------------------------------------- productos

  @Override
  public ProductoDTO agregarProducto(ProductoDTO productoDTO) {
    return agregarProducto(productoDTO, null);
  }

  /**
   * Alta de producto indicando la subcategoria, que es la unidad minima de asignacion. El
   * {@code ProductoDTO} de la catedra no tiene ese campo, por eso viaja aparte.
   */
  public ProductoDTO agregarProducto(ProductoDTO productoDTO, String subcategoriaID) {
    if (productoDTO == null) {
      throw new IllegalArgumentException("El producto no puede ser nulo");
    }
    if (productoDTO.id() != null && !productoDTO.id().isBlank()) {
      throw new IllegalArgumentException("El ID del producto lo genera el modulo");
    }

    Identificador identificador = buscarIdentificadorOpcional(productoDTO.identificadorID());
    validarCategoriaYSubcategoria(productoDTO.categoriaID(), subcategoriaID);

    Producto producto = productoMapper.toModel(productoDTO, subcategoriaID, identificador);
    producto.setId(generadorDeIds.siguiente("producto"));
    Producto guardado = productosRepository.save(producto);

    incrementarMetrica("donatrack.donaciones.productos.registrados");
    publicarEvento("producto", guardado.getId(), "UPSERT", snapshotProducto(guardado));
    return productoMapper.toDTO(guardado);
  }

  /** Modificacion de producto: revalida el identificador porque el nombre pudo cambiar. */
  public ProductoDTO modificarProducto(
      String productoID, ProductoDTO productoDTO, String subcategoriaID) {
    Producto producto = buscarProducto(productoID);

    Identificador identificador = buscarIdentificadorOpcional(productoDTO.identificadorID());
    validarCategoriaYSubcategoria(productoDTO.categoriaID(), subcategoriaID);

    producto.modificar(
        productoDTO.nombre(),
        productoDTO.descripcion(),
        productoDTO.categoriaID(),
        subcategoriaID,
        identificador);

    Producto guardado = productosRepository.save(producto);
    publicarEvento("producto", guardado.getId(), "UPSERT", snapshotProducto(guardado));
    return productoMapper.toDTO(guardado);
  }

  public void eliminarProducto(String productoID) {
    Producto producto = buscarProducto(productoID);

    List<Donacion> donaciones = donacionesRepository.buscarPorProducto(producto.getId());
    if (!donaciones.isEmpty()) {
      throw new RecursoEnUsoException(
          "No se puede eliminar el producto: tiene " + donaciones.size() + " donacion(es) asociada(s)");
    }

    productosRepository.deleteById(producto.getId());
    publicarEvento("producto", producto.getId(), "DELETE", Map.of());
    incrementarMetrica("donatrack.donaciones.productos.eliminados");
  }

  /** El identificador es opcional: un producto puede no tener codigo de barras ni QR. */
  private Identificador buscarIdentificadorOpcional(String identificadorID) {
    if (identificadorID == null || identificadorID.isBlank()) {
      return null;
    }
    return identificadoresRepository
        .findById(identificadorID)
        .orElseThrow(() -> new NoSuchElementException("No existe el identificador indicado"));
  }

  /**
   * Categoria y subcategoria tienen que existir previamente: no se crean en tiempo de ejecucion. Y
   * la subcategoria tiene que colgar efectivamente de la categoria declarada.
   */
  private void validarCategoriaYSubcategoria(String categoriaID, String subcategoriaID) {
    if (categoriaID == null || categoriaID.isBlank()) {
      // La fachada base de la catedra admite productos sin categoria. La API HTTP no: su request
      // exige categoriaID para que toda alta real quede clasificada.
      if (subcategoriaID != null && !subcategoriaID.isBlank()) {
        throw new IllegalArgumentException(
            "No se puede indicar una subcategoria sin indicar su categoria");
      }
      return;
    }

    Categoria categoria =
        categoriasRepository
            .findById(categoriaID)
            .orElseThrow(() -> new NoSuchElementException("No existe la categoria indicada"));

    List<Categoria> subcategorias =
        categoriasRepository.buscarSubcategoriasDe(categoria.getId());

    if (subcategoriaID == null || subcategoriaID.isBlank()) {
      if (!subcategorias.isEmpty()) {
        throw new IllegalArgumentException(
            "La categoria indicada tiene subcategorias: debe seleccionar una");
      }
      return;
    }

    Categoria subcategoria =
        categoriasRepository
            .findById(subcategoriaID)
            .orElseThrow(() -> new NoSuchElementException("No existe la subcategoria indicada"));

    if (!subcategoria.esSubcategoria()) {
      throw new IllegalArgumentException(
          "'" + subcategoria.getNombre() + "' es una categoria raiz, no una subcategoria");
    }
    if (!subcategoria.getCategoriaPadreId().equals(categoria.getId())) {
      throw new IllegalArgumentException(
          "La subcategoria '"
              + subcategoria.getNombre()
              + "' no pertenece a la categoria '"
              + categoria.getNombre()
              + "'");
    }
  }

  @Override
  public ProductoDTO buscarProductoPorID(String productoID) throws NoSuchElementException {
    return productoMapper.toDTO(buscarProducto(productoID));
  }

  public Producto buscarProducto(String productoID) {
    return productosRepository
        .findById(productoID)
        .orElseThrow(() -> new NoSuchElementException("No existe un producto con ese ID"));
  }

  public List<ProductoDTO> listarProductos() {
    return productosRepository.findAll().stream()
        .map(productoMapper::toDTO)
        .collect(Collectors.toList());
  }

  public List<Producto> listarProductosDelDominio() {
    return productosRepository.findAll();
  }

  // --------------------------------------------------------- identificadores

  @Override
  public IdentificadorDTO agregarIdentificador(IdentificadorDTO identificadorDTO) {
    if (identificadorDTO == null) {
      throw new IllegalArgumentException("El identificador no puede ser nulo");
    }
    if (identificadorDTO.id() != null && !identificadorDTO.id().isBlank()) {
      throw new IllegalArgumentException("El ID del identificador lo genera el modulo");
    }

    Identificador identificador = identificadorMapper.toModel(identificadorDTO);
    identificador.setId(generadorDeIds.siguiente("identificador"));
    Identificador guardado = identificadoresRepository.save(identificador);

    incrementarMetrica("donatrack.donaciones.identificadores.registrados");
    return identificadorMapper.toDTO(guardado);
  }

  public IdentificadorDTO modificarIdentificador(
      String identificadorID, IdentificadorDTO identificadorDTO) {
    Identificador identificador = buscarIdentificador(identificadorID);

    identificador.modificar(
        identificadorMapper.toModel(identificadorDTO.tipo()), identificadorDTO.descripcion());

    // Cambiar el tipo puede invalidar productos que ya lo usan.
    List<Producto> productos = productosRepository.buscarPorIdentificador(identificador.getId());
    for (Producto producto : productos) {
      if (!identificador.esValidoPara(producto.getNombre(), producto.getDescripcion())) {
        throw new RecursoEnUsoException(
            "El producto '"
                + producto.getNombre()
                + "' dejaria de cumplir la validacion del identificador");
      }
    }

    return identificadorMapper.toDTO(identificadoresRepository.save(identificador));
  }

  public void eliminarIdentificador(String identificadorID) {
    Identificador identificador = buscarIdentificador(identificadorID);

    List<Producto> productos = productosRepository.buscarPorIdentificador(identificador.getId());
    if (!productos.isEmpty()) {
      throw new RecursoEnUsoException(
          "No se puede eliminar el identificador: lo usan " + productos.size() + " producto(s)");
    }

    identificadoresRepository.deleteById(identificador.getId());
  }

  @Override
  public IdentificadorDTO buscarIdentificadorPorID(String identificadorID)
      throws NoSuchElementException {
    return identificadorMapper.toDTO(buscarIdentificador(identificadorID));
  }

  private Identificador buscarIdentificador(String identificadorID) {
    return identificadoresRepository
        .findById(identificadorID)
        .orElseThrow(() -> new NoSuchElementException("No existe un identificador con ese ID"));
  }

  public List<IdentificadorDTO> listarIdentificadores() {
    return identificadoresRepository.findAll().stream()
        .map(identificadorMapper::toDTO)
        .collect(Collectors.toList());
  }

  // ------------------------------------------------------------- categorias

  public CategoriaDTO agregarCategoria(CategoriaDTO categoriaDTO) {
    return agregarCategoria(categoriaDTO, categoriaDTO == null ? null : categoriaDTO.subcategoriaID());
  }

  /**
   * Alta de categoria. Si se indica {@code categoriaPadreID}, la categoria nueva es una
   * subcategoria de aquella y pasa a ser una unidad de asignacion.
   */
  public CategoriaDTO agregarCategoria(CategoriaDTO categoriaDTO, String categoriaPadreID) {
    if (categoriaDTO == null) {
      throw new IllegalArgumentException("La categoria no puede ser nula");
    }
    if (categoriaDTO.id() != null && !categoriaDTO.id().isBlank()) {
      throw new IllegalArgumentException("El ID de la categoria lo genera el modulo");
    }

    Categoria categoria = categoriaMapper.toModel(categoriaDTO);
    categoria.setId(generadorDeIds.siguiente("categoria"));

    if (categoriaPadreID != null && !categoriaPadreID.isBlank()) {
      Categoria padre = buscarCategoria(categoriaPadreID);
      boolean tieneProductosDirectos =
          productosRepository.buscarPorCategoria(padre.getId()).stream()
              .anyMatch(producto -> producto.getSubcategoriaId() == null);
      if (tieneProductosDirectos) {
        throw new RecursoEnUsoException(
            "No se puede agregar una subcategoria: la categoria clasifica productos directamente");
      }
      categoria.colgarDe(padre);
    }

    Categoria guardada = categoriasRepository.save(categoria);

    incrementarMetrica("donatrack.donaciones.categorias.registradas");
    publicarEvento("categoria", guardada.getId(), "UPSERT", snapshotCategoria(guardada));
    return categoriaMapper.toDTO(guardada);
  }

  public CategoriaDTO modificarCategoria(String categoriaID, CategoriaDTO categoriaDTO) {
    Categoria categoria = buscarCategoria(categoriaID);
    categoria.modificar(categoriaDTO.nombre(), categoriaDTO.descripcion());
    Categoria guardada = categoriasRepository.save(categoria);
    publicarEvento("categoria", guardada.getId(), "UPSERT", snapshotCategoria(guardada));
    return categoriaMapper.toDTO(guardada);
  }

  public void eliminarCategoria(String categoriaID) {
    Categoria categoria = buscarCategoria(categoriaID);

    List<Categoria> subcategorias = categoriasRepository.buscarSubcategoriasDe(categoria.getId());
    if (!subcategorias.isEmpty()) {
      throw new RecursoEnUsoException(
          "No se puede eliminar la categoria: tiene " + subcategorias.size() + " subcategoria(s)");
    }

    List<Producto> productos = productosRepository.buscarPorCategoria(categoria.getId());
    if (!productos.isEmpty()) {
      throw new RecursoEnUsoException(
          "No se puede eliminar la categoria: la usan " + productos.size() + " producto(s)");
    }

    categoriasRepository.deleteById(categoria.getId());
    publicarEvento("categoria", categoria.getId(), "DELETE", Map.of());
  }

  public CategoriaDTO buscarCategoriaPorID(String categoriaID) throws NoSuchElementException {
    return categoriaMapper.toDTO(buscarCategoria(categoriaID));
  }

  public Categoria verCategoria(String categoriaID) {
    return buscarCategoria(categoriaID);
  }

  public List<Categoria> listarCategoriasDelDominio() {
    return categoriasRepository.findAll();
  }

  public List<Categoria> listarSubcategoriasDelDominio(String categoriaID) {
    return categoriasRepository.buscarSubcategoriasDe(buscarCategoria(categoriaID).getId());
  }

  private Categoria buscarCategoria(String categoriaID) {
    return categoriasRepository
        .findById(categoriaID)
        .orElseThrow(() -> new NoSuchElementException("No existe una categoria con ese ID"));
  }

  public List<CategoriaDTO> listarCategorias() {
    return categoriasRepository.findAll().stream()
        .map(categoriaMapper::toDTO)
        .collect(Collectors.toList());
  }

  /** Subcategorias de una categoria: las unidades minimas de asignacion que la componen. */
  public List<CategoriaDTO> listarSubcategoriasDe(String categoriaID) {
    Categoria categoria = buscarCategoria(categoriaID);
    return categoriasRepository.buscarSubcategoriasDe(categoria.getId()).stream()
        .map(categoriaMapper::toDTO)
        .collect(Collectors.toList());
  }

  // ----------------------------------------------------------------- admin

  public void limpiarDatos() {
    donacionesRepository.findAll().forEach(donacion ->
        publicarEvento("donacion", donacion.getId(), "DELETE", Map.of()));
    productosRepository.findAll().forEach(producto ->
        publicarEvento("producto", producto.getId(), "DELETE", Map.of()));
    categoriasRepository.findAll().forEach(categoria ->
        publicarEvento("categoria", categoria.getId(), "DELETE", Map.of()));
    donacionesRepository.deleteAll();
    productosRepository.deleteAll();
    identificadoresRepository.deleteAll();
    categoriasRepository.deleteAll();
  }

  public Producto verProducto(String productoID) {
    return buscarProducto(productoID);
  }
  private void publicarEvento(
      String tipo, String id, String operacion, Map<String, Object> payload) {
    if (readModelEventPublisher != null) {
      readModelEventPublisher.publicar(tipo, id, operacion, payload);
    }
  }

  private Map<String, Object> snapshotDonacion(Donacion donacion) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", donacion.getId());
    snapshot.put("donadorID", donacion.getDonadorId());
    snapshot.put("depositoID", donacion.getDepositoId());
    snapshot.put("descripcion", donacion.getDescripcion());
    snapshot.put("productoID", donacion.getProductoId());
    snapshot.put("cantidad", donacion.getCantidad());
    snapshot.put("estado", donacion.getEstadoActual().name());
    snapshot.put("fechaIngreso", donacion.getFechaIngresoExacta());
    snapshot.put("historialEstados", donacion.getHistorialEstados().stream()
        .map(cambio -> Map.of(
            "estado", cambio.getEstado().name(),
            "fechaCambio", cambio.getFechaCambio(),
            "detalle", cambio.getDetalle() == null ? "" : cambio.getDetalle()))
        .toList());
    return snapshot;
  }

  private Map<String, Object> snapshotProducto(Producto producto) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", producto.getId());
    snapshot.put("nombre", producto.getNombre());
    snapshot.put("descripcion", producto.getDescripcion());
    snapshot.put("categoriaID", producto.getCategoriaId());
    snapshot.put("subcategoriaID", producto.getSubcategoriaId());
    snapshot.put("identificadorID", producto.getIdentificadorId());
    return snapshot;
  }

  private Map<String, Object> snapshotCategoria(Categoria categoria) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", categoria.getId());
    snapshot.put("nombre", categoria.getNombre());
    snapshot.put("descripcion", categoria.getDescripcion());
    snapshot.put("categoriaPadreID", categoria.getCategoriaPadreId());
    snapshot.put("esSubcategoria", categoria.esSubcategoria());
    return snapshot;
  }

}
