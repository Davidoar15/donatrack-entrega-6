package ar.edu.utn.dds.k3003;

import ar.edu.utn.dds.k3003.catedra.dtos.donadoresYEntidades.*;
import ar.edu.utn.dds.k3003.catedra.dtos.incentivos.*;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonaciones;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonadoresYEntidades;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaIncentivos;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaLogistica;
import ar.edu.utn.dds.k3003.exceptions.DonadorNoEncontradoException;
import ar.edu.utn.dds.k3003.model.*;
import ar.edu.utn.dds.k3003.repositories.*;
import java.util.*;
import ar.edu.utn.dds.k3003.messaging.ReadModelEventPublisher;

import ar.edu.utn.dds.k3003.catedra.dtos.logistica.AsignacionDTO;



import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import ar.edu.utn.dds.k3003.metrics.DonadorMetricas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional
public class Fachada implements FachadaDonadoresYEntidades {

  
  @Autowired
  private DonadoresRepository donadoresRepository;
  private EntidadesRepository entidadesRepository;
  private NecesidadMaterialRepository necesidadMaterialRepository;
  
  private DonadoresYEntidadesDataMapper dataMapper = new DonadoresYEntidadesDataMapper();
  @Autowired
  private FachadaIncentivos fachadaIncentivos;
  @Autowired
  private FachadaDonaciones fachadaDonaciones;
  @Autowired
  private FachadaLogistica fachadaLogistica;
  private int idCounter = 1;

  @Autowired(required = false)
  private ReadModelEventPublisher readModelEventPublisher;

  @Autowired
  public Fachada(DonadoresRepository donadoresRepository,
                 EntidadesRepository entidadesRepository,
                 NecesidadMaterialRepository necesidadMaterialRepository) {
    this.donadoresRepository = donadoresRepository;
    this.entidadesRepository = entidadesRepository;
    this.necesidadMaterialRepository = necesidadMaterialRepository;
  }

  @Autowired
  private DonadorMetricas metrics;
  public Fachada() {
  }


  public void setFachadaDonaciones(FachadaDonaciones fachadaDonaciones) { this.fachadaDonaciones = fachadaDonaciones;}
  public void setFachadaLogistica(FachadaLogistica fachadaLogistica) { this.fachadaLogistica = fachadaLogistica; }
  @Override
  public void setFachadaIncentivos(FachadaIncentivos fachadaIncentivos) {
    this.fachadaIncentivos = fachadaIncentivos;
  }



  @Override
  public DonadorDTO agregarDonador(@Valid @RequestBody DonadorDTO donadorDTO) {
    if (donadorDTO == null) throw new RuntimeException();
    //genero id si no se le asignó
    String id = (donadorDTO.id() == null || donadorDTO.id().isBlank())
            ? java.util.UUID.randomUUID().toString()
            : donadorDTO.id();
    if (donadoresRepository.findById(id).isPresent()) {
        throw new NoSuchElementException("Error: Ya existe un donador con el ID: " + donadorDTO.id());
    }

    Donador donadorModel = dataMapper.toDonador(donadorDTO);
    donadorModel.setId(id);
    donadorModel.setEstado(EstadoDonadorEnum.VERIFICADO);
    donadorModel.setCategoria(CategoriaDonadorEnum.OCASIONAL.name());
    donadorModel.registrarCategoriaInicial();
    donadoresRepository.save(donadorModel);
    metrics.donadorRegistrado();
    publicarEvento("donador", donadorModel.getId(), "UPSERT", snapshotDonador(donadorModel));
    return dataMapper.toDonadorDTO(donadorModel);
  }

  @Override
  public DonadorDTO buscarDonadorPorID(String donadorID) {
    return donadoresRepository.findById(donadorID)
        .map(dataMapper::toDonadorDTO)
        .orElseThrow(() -> new NoSuchElementException("Donador no encontrado con id: " + donadorID));
  }

  @Override
  public EntidadBeneficaDTO agregarEntidad(EntidadBeneficaDTO entidadDTO) {
    if (entidadDTO == null) throw new RuntimeException();
    //genero id
    String id = (entidadDTO.id() == null || entidadDTO.id().isBlank())
            ? java.util.UUID.randomUUID().toString()
            : entidadDTO.id();
    if (entidadesRepository.findById(id).isPresent()) {
      throw new RuntimeException("ya existe una entidad con el id: " + id);
    }

    EntidadBenefica entidad = dataMapper.toEntidad(entidadDTO);
    entidad.setId(id);
    entidadesRepository.save(entidad);
    publicarEvento("entidad", entidad.getId(), "UPSERT", snapshotEntidad(entidad));
    return dataMapper.toEntidadDTO(entidad);
  }

  @Override
  public EntidadBeneficaDTO buscarEntidadPorID(String entidadID) {
    return entidadesRepository.findById(entidadID)
        .map(dataMapper::toEntidadDTO)
        .orElseThrow(() -> new NoSuchElementException("Entidad no encontrada"));
  }

  @Override
  @Transactional
  public NecesidadMaterialDTO registrarNecesidad(NecesidadMaterialDTO necesidadDTO) {
    return registrarNecesidad(necesidadDTO, PeriodoNecesidad.SEMANAL);
  }

  public NecesidadMaterialDTO registrarNecesidad(NecesidadMaterialDTO necesidadDTO, PeriodoNecesidad periodo) {
    if (necesidadDTO == null || necesidadDTO.id() != null) throw new IllegalArgumentException("Necesidad inválida");
    validarNecesidad(necesidadDTO);
    if (fachadaDonaciones != null) {
      try {
        fachadaDonaciones.buscarProductoPorID(necesidadDTO.productoSolicitadoID());
      } catch (NoSuchElementException e) {
        throw new IllegalArgumentException("producto solicitado no válido en Donaciones.");
      }
    }

    EntidadBenefica entidadBenefica = entidadesRepository.findById(necesidadDTO.entidadID())
            .orElseThrow(() -> new NoSuchElementException("Entidad no encontrada"));

    int cantidadAAsignar = 0;
    if (fachadaLogistica != null) {
      var stock = fachadaLogistica.consultarStock(necesidadDTO.productoSolicitadoID());
      if (stock != null) {
        int stockDisponible = Math.max(0, stock.cantidadDisponible());
        cantidadAAsignar = Math.min(necesidadDTO.cantidadObjetivo(), stockDisponible);
      }
    }

    NecesidadMaterial necesidadMaterial = dataMapper.toNecesidad(necesidadDTO);
    necesidadMaterial.setPeriodo(periodo);
    necesidadMaterial.actualizarPeriodo(java.time.LocalDate.now());
    necesidadMaterial.setId(java.util.UUID.randomUUID().toString());
    // La cantidad recibida se actualiza al entregar, no al reservar stock.
    necesidadMaterial.setCantidadAsignada(0);
    necesidadMaterial.setEntidadBenefica(entidadBenefica);
    entidadBenefica.agregarNecesidad(necesidadMaterial);
    entidadesRepository.saveAndFlush(entidadBenefica);

    if (necesidadDTO.tipo() == TipoNecesidadMaterialEnum.RECURRENTE && cantidadAAsignar < necesidadDTO.cantidadObjetivo()) cantidadAAsignar = 0;
    if (fachadaLogistica != null && cantidadAAsignar > 0) {
        fachadaLogistica.asignarDesdeStock(
                necesidadMaterial.getId(),
                necesidadDTO.productoSolicitadoID(),
                cantidadAAsignar
        );
    }

    publicarEvento("necesidad", necesidadMaterial.getId(), "UPSERT", snapshotNecesidad(necesidadMaterial));
    publicarEvento("entidad", entidadBenefica.getId(), "UPSERT", snapshotEntidad(entidadBenefica));
    metrics.necesidadRegistrada();
    ar.edu.utn.dds.k3003.observability.DomainEvents.info(org.slf4j.LoggerFactory.getLogger(Fachada.class),
        "necesidad.registrada id={} tipo={} objetivo={} reservada={}",
        necesidadMaterial.getId(), necesidadDTO.tipo(), necesidadDTO.cantidadObjetivo(), cantidadAAsignar);
    return dataMapper.toNecesidadDTO(necesidadMaterial);


  }

  @Override
  public QuejaDTO agregarQueja(QuejaDTO quejaDTO) {
    if (quejaDTO == null || quejaDTO.id() != null) throw new RuntimeException();
    
    Donador donador = donadoresRepository.findById(quejaDTO.donadorID())
            .orElseThrow(() -> new DonadorNoEncontradoException("Donador no encontrado"));

    Queja queja = new Queja(
            java.util.UUID.randomUUID().toString(),
            quejaDTO.donadorID(),
            quejaDTO.donacionID(),
            quejaDTO.descripcion(),
            quejaDTO.fecha()
    );
    donador.registrarQueja(queja);


    donadoresRepository.save(donador);
    publicarEvento("queja", queja.getId(), "UPSERT", snapshotQueja(queja));
    publicarEvento("donador", donador.getId(), "UPSERT", snapshotDonador(donador));
    metrics.quejaRegistrada();
    if (donador.getEstado() == EstadoDonadorEnum.BANEADO && donador.getListaDeQuejas().size() == 10) metrics.donadorBaneado();

    return dataMapper.toQuejaDTO(queja);

  }

  @Override
  public List<QuejaDTO> obtenerQuejasDe(String donadorID) {
    Donador donador = donadoresRepository.findById(donadorID)
        .orElseThrow(() -> new NoSuchElementException("Donador inexistente"));
    return donador.getListaDeQuejas().stream().map(dataMapper::toQuejaDTO).collect(Collectors.toList());
  }

  public List<QuejaDTO> obtenerTodasLasQuejas() {

    List<Donador> todosLosDonadores = donadoresRepository.findAll();
    
    return todosLosDonadores.stream()
            .flatMap(donador -> donador.getListaDeQuejas().stream())
            .map(dataMapper::toQuejaDTO)
            .collect(Collectors.toList());
  }

  @Override
  public Boolean puedeDonar(String donadorID) {
    if (donadorID == null) throw new IllegalArgumentException("ID no puede ser nulo");
    return donadoresRepository.findById(donadorID)
            .map(Donador::puedeHacerDonacion)
            .orElseThrow(() -> new NoSuchElementException("ID no encontrado: " + donadorID));
  }

  @Override
  public DonadorDTO modificarEstado(String donadorID, EstadoDonadorEnum nuevoEstado) {
    if (donadorID == null || nuevoEstado == null) throw new RuntimeException();
    Donador donador = donadoresRepository.findById(donadorID).orElseThrow(() -> new NoSuchElementException());
    EstadoDonadorEnum estadoAnterior = donador.getEstado();
    donador.cambiarEstado(nuevoEstado);
    if (nuevoEstado == EstadoDonadorEnum.BANEADO && estadoAnterior != nuevoEstado) {
      metrics.donadorBaneado();
    }
    Donador guardado = donadoresRepository.save(donador);
    publicarEvento("donador", guardado.getId(), "UPSERT", snapshotDonador(guardado));
    return dataMapper.toDonadorDTO(guardado);
  }

  public DonadorDTO modificarDonador(String donadorID, DonadorDTO donadorDTO) {
    if (donadorID == null || donadorDTO == null) {
      throw new IllegalArgumentException("ID o DTO de donador inválidos");
    }
    Donador donador = donadoresRepository.findById(donadorID)
            .orElseThrow(() -> new NoSuchElementException("Donador no encontrado: " + donadorID));


    if (donadorDTO.nombre() != null) {
      donador.setNombre(donadorDTO.nombre());
    }
    if (donadorDTO.apellido() != null) {
      donador.setApellido(donadorDTO.apellido());
    }
    if (donadorDTO.email() != null) {
      donador.setEmail(donadorDTO.email());
    }
    if (donadorDTO.edad() != null) {
      donador.setEdad(donadorDTO.edad());
    }
    if (donadorDTO.domicilio() != null) {
      donador.setDomicilio(donadorDTO.domicilio());
    }
    if (donadorDTO.nroDocumento() != null) {
      donador.setNroDocumento(donadorDTO.nroDocumento());
    }

    donadoresRepository.save(donador);
    publicarEvento("donador", donador.getId(), "UPSERT", snapshotDonador(donador));
    return dataMapper.toDonadorDTO(donador);
  }

  @Override
  public DonadorDTO modifcarCategoria(String donadorID, String nuevaCategoria) {
    if (donadorID == null || nuevaCategoria == null) throw new RuntimeException();
    Donador donador = donadoresRepository.findById(donadorID).orElseThrow(() -> new NoSuchElementException());
    CategoriaDonadorEnum.valueOf(nuevaCategoria);
    donador.cambiarCategoria(nuevaCategoria);
    donadoresRepository.save(donador);
    publicarEvento("donador", donador.getId(), "UPSERT", snapshotDonador(donador));
    return dataMapper.toDonadorDTO(donador);
  }

  @Override
  public List<NecesidadMaterialDTO> obtenerNecesidadesInsatisfechasDe(String productoID) {
    List<NecesidadMaterialDTO> necesidadesInsatisfechas = new ArrayList<>();
    entidadesRepository.findAll().forEach(entidad -> {
      entidad.getNecesidades().stream()
          .peek(necesidad -> necesidad.actualizarPeriodo(java.time.LocalDate.now()))
          .filter(necesidad -> productoID.equals(necesidad.getProductoSolicitadoID())
                  && necesidad.getCantidadAsignada() < necesidad.getCantidadObjetivo())
          .map(dataMapper::toNecesidadDTO)
          .forEach(necesidadesInsatisfechas::add);
    });
    return necesidadesInsatisfechas;
  }

@Override
  public NecesidadMaterialDTO satisfacerNecesidad(String necesidadID, Integer cantidadASatisfacer) {
    if (necesidadID == null || cantidadASatisfacer == null || cantidadASatisfacer <= 0) throw new RuntimeException();
    
    for (EntidadBenefica entidadBenefica : entidadesRepository.findAll()) {
      for (NecesidadMaterial necesidadMaterial : entidadBenefica.getNecesidades()) {
        if (necesidadID.equals(necesidadMaterial.getId())) {
          necesidadMaterial.actualizarPeriodo(java.time.LocalDate.now());
          
          if (necesidadMaterial.getTipo() == TipoNecesidadMaterialEnum.RECURRENTE) {
              if (cantidadASatisfacer < necesidadMaterial.getCantidadObjetivo() || necesidadMaterial.getCantidadAsignada() > 0) {
                  throw new RuntimeException("No se aceptan donaciones parciales para necesidades recurrentes");
              }
          }

          necesidadMaterial.satisfacer(cantidadASatisfacer);
          
          entidadesRepository.save(entidadBenefica);
          publicarEvento("necesidad", necesidadMaterial.getId(), "UPSERT", snapshotNecesidad(necesidadMaterial));
          publicarEvento("entidad", entidadBenefica.getId(), "UPSERT", snapshotEntidad(entidadBenefica));
          metrics.unidadesEntregadas(cantidadASatisfacer);
          ar.edu.utn.dds.k3003.observability.DomainEvents.info(org.slf4j.LoggerFactory.getLogger(Fachada.class),
              "necesidad.entrega_registrada id={} cantidad={}", necesidadID, cantidadASatisfacer);
          
          return dataMapper.toNecesidadDTO(necesidadMaterial);
        }
      }
    }
    throw new NoSuchElementException("Necesidad no encontrada con id: " + necesidadID);
  }

  @Override
  public DonadorStatsDTO estadisticasDonador(String donadorID) {
    Donador donador = donadoresRepository.findById(donadorID).orElseThrow(() -> new NoSuchElementException());
    
    List<String> insigniasIds = (fachadaIncentivos != null) ?
        fachadaIncentivos.getInsigniasDeDonador(donadorID).stream().map(InsigniaDTO::id).collect(Collectors.toList())
        : new ArrayList<>();
    
    MisionDTO misionEnCurso = (fachadaIncentivos != null) ? fachadaIncentivos.getMisionEnCursoDeDonador(donadorID) : null;
    String misionID;
      if (misionEnCurso != null) {
          misionID = misionEnCurso.id();
      } else {
          misionID = null;
      }

    return new DonadorStatsDTO(donador.getId(), donador.getNombre(), donador.getApellido(), donador.getEdad(), 
                               donador.getEstado(), donador.getCategoria(), misionID, insigniasIds);
  }


  @Transactional
  public EntidadBeneficaDTO modificarEntidad(String entidadID, EntidadBeneficaDTO entidadDTO) {
    if (entidadID == null || entidadDTO == null) throw new IllegalArgumentException("Datos inválidos");

    EntidadBenefica entidad = entidadesRepository.findById(entidadID)
            .orElseThrow(() -> new NoSuchElementException("Entidad no encontrada con ID: " + entidadID));

    if (entidadDTO.razonSocial() != null) {
      entidad.setRazonSocial(entidadDTO.razonSocial());
    }
    if (entidadDTO.domicilio() != null) {
      entidad.setDomicilio(entidadDTO.domicilio());
    }
    if (entidadDTO.telefono() != null) {
      entidad.setTelefono(entidadDTO.telefono());
    }
    if (entidadDTO.correo() != null) {
      entidad.setCorreo(entidadDTO.correo());
    }

    entidadesRepository.save(entidad);
    publicarEvento("entidad", entidad.getId(), "UPSERT", snapshotEntidad(entidad));
    return dataMapper.toEntidadDTO(entidad);
  }

  public NecesidadMaterialDTO buscarNecesidadPorID(String necesidadID) {
    if (necesidadID == null) throw new IllegalArgumentException("ID inválido");

    return necesidadMaterialRepository.findById(necesidadID)
            .map(n -> { n.actualizarPeriodo(java.time.LocalDate.now()); return dataMapper.toNecesidadDTO(n); })
            .orElseThrow(() -> new NoSuchElementException("Necesidad no encontrada con ID: " + necesidadID));
  }

  @Transactional
  public NecesidadMaterialDTO modificarNecesidad(String necesidadID, NecesidadMaterialDTO necesidadDTO) {
    if (necesidadID == null || necesidadDTO == null) throw new IllegalArgumentException("Datos inválidos");

    for (EntidadBenefica entidad : entidadesRepository.findAll()) {
      for (NecesidadMaterial necesidad : entidad.getNecesidades()) {
        if (necesidadID.equals(necesidad.getId())) {

          validarNecesidad(necesidadDTO);
          if (!Objects.equals(necesidad.getEntidadID(), necesidadDTO.entidadID())
              || !Objects.equals(necesidad.getProductoSolicitadoID(), necesidadDTO.productoSolicitadoID())
              || necesidad.getTipo() != necesidadDTO.tipo()) {
            throw new IllegalArgumentException("Para cambiar entidad, producto o tipo, cree otra necesidad");
          }
          if (necesidadDTO.cantidadObjetivo() < necesidad.getCantidadAsignada()) {
            throw new IllegalArgumentException("El objetivo no puede ser menor que la cantidad ya recibida");
          }
          necesidad.setDescripcion(necesidadDTO.descripcion());
          necesidad.setNivelDeUrgencia(necesidadDTO.nivelDeUrgencia());
          necesidad.setCantidadObjetivo(necesidadDTO.cantidadObjetivo());

          entidadesRepository.save(entidad);
          publicarEvento("necesidad", necesidad.getId(), "UPSERT", snapshotNecesidad(necesidad));
          publicarEvento("entidad", entidad.getId(), "UPSERT", snapshotEntidad(entidad));
          return dataMapper.toNecesidadDTO(necesidad);
        }
      }
    }
    throw new NoSuchElementException("Necesidad no encontrada: " + necesidadID);
  }

  @Transactional
  public void borrarNecesidad(String necesidadID) {
    if (necesidadID == null) throw new IllegalArgumentException("ID inválido");

    if (!necesidadMaterialRepository.existsById(necesidadID)) {
      throw new NoSuchElementException("Necesidad no encontrada con ID: " + necesidadID);
    }

    necesidadMaterialRepository.deleteById(necesidadID);
    publicarEvento("necesidad", necesidadID, "DELETE", Map.of());
  }


  @Transactional
  public void limpiarDatos() {
    donadoresRepository.findAll().forEach(donador -> {
      donador.getListaDeQuejas().forEach(queja ->
          publicarEvento("queja", queja.getId(), "DELETE", Map.of()));
      publicarEvento("donador", donador.getId(), "DELETE", Map.of());
    });
    entidadesRepository.findAll().forEach(entidad -> {
      entidad.getNecesidades().forEach(necesidad ->
          publicarEvento("necesidad", necesidad.getId(), "DELETE", Map.of()));
      publicarEvento("entidad", entidad.getId(), "DELETE", Map.of());
    });
    necesidadMaterialRepository.deleteAll();
    donadoresRepository.deleteAll();
    entidadesRepository.deleteAll();
  }

  private void validarNecesidad(NecesidadMaterialDTO dto) {
    if (dto.entidadID() == null || dto.entidadID().isBlank()
        || dto.productoSolicitadoID() == null || dto.productoSolicitadoID().isBlank()
        || dto.descripcion() == null || dto.descripcion().isBlank()
        || dto.cantidadObjetivo() == null || dto.cantidadObjetivo() <= 0
        || dto.nivelDeUrgencia() == null || dto.nivelDeUrgencia() < 1 || dto.nivelDeUrgencia() > 10
        || dto.tipo() == null) throw new IllegalArgumentException("Datos de necesidad inválidos");
  }

  public List<DonadorDTO> listarDonadores() {
      List<DonadorDTO> dtos = new ArrayList<>();
      
      for (Donador d : donadoresRepository.findAll()) {
          
          dtos.add(dataMapper.toDonadorDTO(d));
      }
      return dtos;
  }

  public List<EntidadBeneficaDTO> listarEntidades() {
      List<EntidadBeneficaDTO> dtos = new ArrayList<>();
      for (EntidadBenefica e : entidadesRepository.findAll()) {
          
          dtos.add(dataMapper.toEntidadDTO(e));
      }
      return dtos;
  }

  public List<NecesidadMaterialDTO> listarNecesidades() {
    return necesidadMaterialRepository.findAll().stream()
        .peek(n -> n.actualizarPeriodo(java.time.LocalDate.now())).map(dataMapper::toNecesidadDTO).toList();
  }

  public java.util.Map<String, Object> periodoNecesidad(String id) {
    NecesidadMaterial necesidad = necesidadMaterialRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Necesidad inexistente"));
    necesidad.actualizarPeriodo(java.time.LocalDate.now());
    java.util.Map<String, Object> datos = new java.util.LinkedHashMap<>();
    datos.put("periodo", necesidad.getPeriodo());
    datos.put("inicio", necesidad.getInicioPeriodo());
    return datos;
  }

  private void publicarEvento(
      String tipo, String id, String operacion, Map<String, Object> payload) {
    if (readModelEventPublisher != null) {
      readModelEventPublisher.publicar(tipo, id, operacion, payload);
    }
  }

  private Map<String, Object> snapshotDonador(Donador donador) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", donador.getId());
    snapshot.put("nombre", donador.getNombre());
    snapshot.put("apellido", donador.getApellido());
    snapshot.put("estado", donador.getEstado() == null ? null : donador.getEstado().name());
    snapshot.put("categoria", donador.getCategoria());
    donador.registrarCategoriaInicial();
    snapshot.put("historialCategorias", List.copyOf(donador.getHistorialCategorias()));
    snapshot.put("historialEstados", donador.getHistorialEstados().stream()
        .map(estado -> estado.name()).toList());
    snapshot.put("quejas", donador.getListaDeQuejas().stream().map(this::snapshotQueja).toList());
    snapshot.put("cantidadQuejas", donador.getListaDeQuejas().size());
    return snapshot;
  }

  private Map<String, Object> snapshotEntidad(EntidadBenefica entidad) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", entidad.getId());
    snapshot.put("razonSocial", entidad.getRazonSocial());
    snapshot.put("domicilio", entidad.getDomicilio());
    snapshot.put("telefono", entidad.getTelefono());
    snapshot.put("correo", entidad.getCorreo());
    return snapshot;
  }

  private Map<String, Object> snapshotNecesidad(NecesidadMaterial necesidad) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", necesidad.getId());
    snapshot.put("entidadID", necesidad.getEntidadID());
    snapshot.put("nivelDeUrgencia", necesidad.getNivelDeUrgencia());
    snapshot.put("descripcion", necesidad.getDescripcion());
    snapshot.put("cantidadObjetivo", necesidad.getCantidadObjetivo());
    snapshot.put("cantidadAsignada", necesidad.getCantidadAsignada());
    snapshot.put("productoSolicitadoID", necesidad.getProductoSolicitadoID());
    snapshot.put("tipo", necesidad.getTipo() == null ? null : necesidad.getTipo().name());
    snapshot.put("periodo", necesidad.getPeriodo() == null ? null : necesidad.getPeriodo().name());
    snapshot.put("inicioPeriodo", necesidad.getInicioPeriodo());
    return snapshot;
  }

  private Map<String, Object> snapshotQueja(Queja queja) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("id", queja.getId());
    snapshot.put("donadorID", queja.getDonadorID());
    snapshot.put("donacionID", queja.getDonacionID());
    snapshot.put("descripcion", queja.getDescripcion());
    snapshot.put("fecha", queja.getFecha());
    return snapshot;
  }

}
