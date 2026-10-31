package ar.edu.utn.dds.k3003;

import ar.edu.utn.dds.k3003.catedra.dtos.donaciones.EstadoDonacionEnum;
import ar.edu.utn.dds.k3003.catedra.dtos.donadoresYEntidades.NecesidadMaterialDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.logistica.AsignacionDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.logistica.DepositoDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.logistica.EstadoAsignacionEnum;
import ar.edu.utn.dds.k3003.catedra.dtos.logistica.PaqueteDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.logistica.StockDTO;
import ar.edu.utn.dds.k3003.catedra.dtos.logistica.TipoAlgoritmoEnum;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonaciones;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaDonadoresYEntidades;
import ar.edu.utn.dds.k3003.catedra.fachadas.FachadaLogistica;
import ar.edu.utn.dds.k3003.controllers.requests.logistica.ResultadoMatchmakingRequest;
import ar.edu.utn.dds.k3003.exceptions.BusinessRuleException;
import ar.edu.utn.dds.k3003.exceptions.ConflictException;
import ar.edu.utn.dds.k3003.exceptions.ResourceNotFoundException;
import ar.edu.utn.dds.k3003.messaging.DonacionRegistradaEvent;
import ar.edu.utn.dds.k3003.messaging.ReadModelEventPublisher;
import ar.edu.utn.dds.k3003.messaging.dto.DonacionPendienteMessage;
import ar.edu.utn.dds.k3003.model.Asignacion;
import ar.edu.utn.dds.k3003.model.Deposito;
import ar.edu.utn.dds.k3003.model.EstadoPaquete;
import ar.edu.utn.dds.k3003.model.OrigenAsignacion;
import ar.edu.utn.dds.k3003.model.Paquete;
import ar.edu.utn.dds.k3003.model.algoritmos.ResultadoMatchmaking;
import ar.edu.utn.dds.k3003.persistence.adapter.AsignacionJpaRepositoryAdapter;
import ar.edu.utn.dds.k3003.persistence.adapter.DepositoJpaRepositoryAdapter;
import ar.edu.utn.dds.k3003.repositories.AsignacionRepository;
import ar.edu.utn.dds.k3003.repositories.DepositoRepository;
import ar.edu.utn.dds.k3003.repositories.LogisticaDataMapper;
import ar.edu.utn.dds.k3003.repositories.inmemory.InMemoryAsignacionRepository;
import ar.edu.utn.dds.k3003.repositories.inmemory.InMemoryDepositoRepository;
import ar.edu.utn.dds.k3003.services.MatchmakingService;
import ar.edu.utn.dds.k3003.services.MatchmakingRegistrationResult;
import ar.edu.utn.dds.k3003.observability.TraceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Fachada implements FachadaLogistica {

    private static final Logger log = LoggerFactory.getLogger(Fachada.class);

    private final DepositoRepository depositoRepo;
    private final AsignacionRepository asignacionRepo;
    private final LogisticaDataMapper mapper;
    private final MatchmakingService matchmakingService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private ReadModelEventPublisher readModelEventPublisher;

    private FachadaDonadoresYEntidades fachadaDonadores;
    private FachadaDonaciones fachadaDonaciones;

    public Fachada() {
        this(
            new InMemoryDepositoRepository(),
            new InMemoryAsignacionRepository(),
            new LogisticaDataMapper(),
            new MatchmakingService(),
            event -> {}
        );
    }

    @Autowired
    public Fachada(
        DepositoRepository depositoRepo,
        AsignacionRepository asignacionRepo,
        LogisticaDataMapper mapper,
        MatchmakingService matchmakingService,
        ApplicationEventPublisher eventPublisher
    ) {
        this.depositoRepo = Objects.requireNonNull(depositoRepo);
        this.asignacionRepo = Objects.requireNonNull(asignacionRepo);
        this.mapper = Objects.requireNonNull(mapper);
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    @Override
    @Transactional
    public DepositoDTO agregarDeposito(DepositoDTO depositoDto) {
        if (depositoDto == null || depositoDto.id() != null) {
            throw new BusinessRuleException("Depósito inválido");
        }

        if (depositoDto.nombre() == null || depositoDto.nombre().isBlank()
            || depositoDto.direccion() == null || depositoDto.direccion().isBlank()
            || depositoDto.capacidadMaxima() == null
            || depositoDto.capacidadMaxima() <= 0) {
            throw new BusinessRuleException("Datos inválidos para el depósito");
        }

        Deposito deposito = mapper.toDeposito(depositoDto);
        Deposito guardado = depositoRepo.save(deposito);
        publicarEvento("deposito", guardado.getId(), "UPSERT", snapshotDeposito(guardado));
        return mapper.toDepositoDTO(guardado);
    }

    @Override
    @Transactional(readOnly = true)
    public DepositoDTO buscarDepositoPorID(String depositoID) throws NoSuchElementException {
        validarId(depositoID, "ID de depósito inválido");

        Deposito deposito = depositoRepo.findById(depositoID)
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));

        return mapper.toDepositoDTO(deposito);
    }

    @Transactional(readOnly = true)
    public List<DepositoDTO> buscarDepositos() {
        return depositoRepo.findAll().stream()
            .map(mapper::toDepositoDTO)
            .toList();
    }

    @Transactional
    public void eliminarDeposito(String depositoID) {
        validarId(depositoID, "ID de depósito inválido");

        Deposito deposito = depositoRepo.findByIdForUpdate(depositoID)
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));
        if (!deposito.getStockActual().isEmpty()) throw new ConflictException("El depósito tiene paquetes; no se puede borrar su trazabilidad");
        depositoRepo.deleteById(depositoID);
        publicarEvento("deposito", depositoID, "DELETE", Map.of());
    }

    @Override
    @Transactional
    public void setAlgoritmoMM(String depositoID, TipoAlgoritmoEnum tipoAlgoritmo) {
        validarId(depositoID, "ID de depósito inválido");

        if (tipoAlgoritmo == null) {
            throw new BusinessRuleException("El algoritmo es obligatorio");
        }

        Deposito deposito = depositoRepo.findByIdForUpdate(depositoID)
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));

        deposito.setTipoAlgoritmo(tipoAlgoritmo);
        Deposito guardado = depositoRepo.save(deposito);
        publicarEvento("deposito", guardado.getId(), "UPSERT", snapshotDeposito(guardado));
    }

    @Override
    @Transactional
    public DepositoDTO gestionarDonacion(
        String depositoID,
        String donacionID,
        String productoID,
        Integer cantidad
    ) {
        validarId(depositoID, "ID de depósito inválido");
        validarId(donacionID, "ID de donación inválido");
        validarId(productoID, "ID de producto inválido");

        if (cantidad == null || cantidad <= 0) {
            throw new BusinessRuleException("La cantidad donada debe ser positiva");
        }

        Deposito deposito = depositoRepo.findByIdForUpdate(depositoID)
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));

        if (deposito.getTipoAlgoritmo() == null) {
            throw new ConflictException("El depósito no tiene algoritmo configurado");
        }

        if (!deposito.tieneLugar(cantidad)) {
            throw new ConflictException("El depósito no tiene capacidad suficiente");
        }

        Paquete paquete = new Paquete(
            donacionID,
            productoID,
            cantidad,
            EstadoPaquete.PENDIENTE
        );

        deposito.almacenar(paquete);
        Deposito guardado = depositoRepo.save(deposito);

        if (paquete.getId() == null) {
            throw new IllegalStateException("No se pudo identificar el paquete persistido");
        }

        publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(guardado.getId(), paquete));
        publicarEvento("deposito", guardado.getId(), "UPSERT", snapshotDeposito(guardado));

        eventPublisher.publishEvent(
            new DonacionRegistradaEvent(
                new DonacionPendienteMessage(
                    guardado.getId(),
                    paquete.getId(),
                    donacionID,
                    productoID,
                    cantidad,
                    guardado.getTipoAlgoritmo(),
                    TraceContext.currentTraceId()
                )
            )
        );

        ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
            "donacion.registrada deposito={} paquete={} donacion={} producto={} cantidad={} algoritmo={}",
            guardado.getId(),
            paquete.getId(),
            donacionID,
            productoID,
            cantidad,
            guardado.getTipoAlgoritmo()
        );

        return mapper.toDepositoDTO(guardado);
    }

    @Override
    @Transactional
    public AsignacionDTO ejecutarMatchmaking(
        String depositoID,
        PaqueteDTO paqueteDTO,
        List<NecesidadMaterialDTO> necesidadesMateriales
    ) {
        validarId(depositoID, "ID de depósito inválido");

        if (paqueteDTO == null || paqueteDTO.id() == null) {
            throw new BusinessRuleException("Paquete inválido");
        }

        Deposito deposito = depositoRepo.findByIdForUpdate(depositoID)
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));

        if (deposito.getTipoAlgoritmo() == null) {
            throw new ConflictException("El depósito no tiene algoritmo configurado");
        }

        ResultadoMatchmaking resultado = matchmakingService.procesar(
            deposito.getTipoAlgoritmo(),
            necesidadesMateriales,
            paqueteDTO.producto(),
            paqueteDTO.cantidad(),
            necesidadId -> cantidadAsignadaPorNecesidad(necesidadId)
        );

        Paquete paquete = buscarPaquete(deposito, paqueteDTO.id());

        return registrarResultadoInternamente(
            deposito,
            paquete,
            resultado
        );
    }

    @Override
    @Transactional
    public void reportarEntrega(PaqueteDTO paqueteDTO) {
        if (paqueteDTO == null || paqueteDTO.id() == null) {
            throw new BusinessRuleException("Paquete inválido");
        }

        asegurarIntegraciones();

        Deposito deposito = encontrarDepositoPorPaquete(paqueteDTO.id());
        deposito = depositoRepo.findByIdForUpdate(deposito.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));

        Asignacion asignacion = asignacionRepo.findByPaqueteId(paqueteDTO.id())
            .orElseThrow(() -> new ResourceNotFoundException("Asignación no encontrada"));

        if (asignacion.getEstado() != EstadoAsignacionEnum.ASIGNADA) {
            throw new ConflictException("La asignación no está en estado ASIGNADA");
        }

        java.time.LocalDate inicio = inicioPeriodo(asignacion.getNecesidadId());
        if (inicio != null && asignacion.getFecha().toLocalDate().isBefore(inicio))
            throw new ConflictException("La asignación corresponde a un período anterior");

        Paquete paquete = buscarPaquete(deposito, paqueteDTO.id());

        asignacion.cambiarEstado(EstadoAsignacionEnum.COMPLETADA);
        asignacionRepo.save(asignacion);

        fachadaDonadores.satisfacerNecesidad(
            asignacion.getNecesidadId(),
            asignacion.getCantidadAsignada()
        );

        paquete.marcarEntregado();
        depositoRepo.save(deposito);

        aceptarDonacion(paquete.getDonacionId());
        publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete));
        publicarEvento("asignacion", asignacion.getId(), "UPSERT", snapshotAsignacion(deposito, asignacion));
        publicarEvento("deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito));

        registrarDuracionEntrega(asignacion);

        ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
            "entrega.reportada paquete={} deposito={} necesidad={} cantidad={} donacion={}",
            paquete.getId(),
            deposito.getId(),
            asignacion.getNecesidadId(),
            asignacion.getCantidadAsignada(),
            paquete.getDonacionId()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public AsignacionDTO buscarAsignacionPorPaqueteID(String paqueteID) throws NoSuchElementException {
        validarId(paqueteID, "ID de paquete inválido");

        Asignacion asignacion = asignacionRepo.findByPaqueteId(paqueteID)
            .orElseThrow(() -> new ResourceNotFoundException("Asignación no encontrada"));

        return mapper.toAsignacionDTO(asignacion);
    }

    @Transactional(readOnly = true)
    public AsignacionDTO buscarAsignacionPorID(String asignacionID) {
        validarId(asignacionID, "ID de asignación inválido");

        Asignacion asignacion = asignacionRepo.findById(asignacionID)
            .orElseThrow(() -> new ResourceNotFoundException("Asignación no encontrada"));

        return mapper.toAsignacionDTO(asignacion);
    }

    @Override
    public void setFachadaDonadoresYEntidades(FachadaDonadoresYEntidades fachadaDonadoresYEntidades) {
        this.fachadaDonadores = fachadaDonadoresYEntidades;
    }

    @Override
    public void setFachadaDonaciones(FachadaDonaciones fachadaDonaciones) {
        this.fachadaDonaciones = fachadaDonaciones;
    }

    @Transactional
    public AsignacionDTO registrarResultadoMatchmaking(
        ResultadoMatchmakingRequest request
    ) {
        return registrarResultadoMatchmakingDetallado(request).asignacion();
    }

    @Transactional
    public MatchmakingRegistrationResult registrarResultadoMatchmakingDetallado(
        ResultadoMatchmakingRequest request
    ) {
        validarResultado(request);

        Deposito deposito = depositoRepo.findByIdForUpdate(request.depositoId())
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));

        Paquete paquete = buscarPaquete(deposito, request.paqueteId());

        if (paquete.getEstadoPaquete() == EstadoPaquete.EN_STOCK) {
            ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
                "matchmaking.resultado_duplicado paquete={} estado=EN_STOCK",
                paquete.getId()
            );
            return new MatchmakingRegistrationResult(null, false, 0);
        }

        if (paquete.getEstadoPaquete() == EstadoPaquete.ASIGNADO || paquete.getEstadoPaquete() == EstadoPaquete.ENTREGADO) {
            Asignacion existente = asignacionRepo.findByPaqueteId(paquete.getId())
                .orElseThrow(() -> new IllegalStateException("Paquete asignado sin asignación"));

            ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
                "matchmaking.resultado_duplicado paquete={} asignacion={}",
                paquete.getId(),
                existente.getId()
            );
            return new MatchmakingRegistrationResult(
                mapper.toAsignacionDTO(existente),
                false,
                0
            );
        }

        if (paquete.getEstadoPaquete() != EstadoPaquete.PENDIENTE) {
            throw new ConflictException(
                "El paquete no puede procesar un resultado de matchmaking"
            );
        }

        int cantidadOriginal = paquete.getCantidad();

        if (request.cantidadAsignada() + request.cantidadSobrante() != cantidadOriginal) {
            throw new BusinessRuleException(
                "El resultado no coincide con la cantidad original"
            );
        }

        if (!request.tieneAsignacion()) {
            paquete.marcarEnStock();
            deposito = depositoRepo.save(deposito);
            publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete));
            publicarEvento("deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito));

            ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
                "matchmaking.sin_asignacion paquete={} cantidad={} -> EN_STOCK",
                paquete.getId(),
                cantidadOriginal
            );
            return new MatchmakingRegistrationResult(null, true, cantidadOriginal);
        }

        if (request.cantidadAsignada() <= 0 || request.necesidadId() == null) {
            throw new BusinessRuleException("Resultado de asignación inválido");
        }

        paquete.setCantidad(request.cantidadAsignada());
        paquete.marcarAsignado();

        Asignacion asignacion = new Asignacion(
            paquete.getId(),
            request.necesidadId(),
            LocalDateTime.now(),
            EstadoAsignacionEnum.ASIGNADA,
            request.cantidadAsignada(),
            OrigenAsignacion.MATCHMAKING
        );

        Asignacion guardada = asignacionRepo.save(asignacion);

        if (request.cantidadSobrante() > 0) {
            deposito.almacenar(
                new Paquete(
                    paquete.getDonacionId(),
                    paquete.getProducto(),
                    request.cantidadSobrante(),
                    EstadoPaquete.EN_STOCK
                )
            );
        }

        deposito = depositoRepo.save(deposito);
        publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete));
        publicarEvento("asignacion", guardada.getId(), "UPSERT", snapshotAsignacion(deposito, guardada));
        publicarEvento("deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito));
        publicarPaquetes(deposito);

        ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
            "matchmaking.asignado paquete={} asignacion={} necesidad={} cantidad={} sobrante={}",
            paquete.getId(),
            guardada.getId(),
            request.necesidadId(),
            request.cantidadAsignada(),
            request.cantidadSobrante()
        );

        return new MatchmakingRegistrationResult(
            mapper.toAsignacionDTO(guardada),
            true,
            request.cantidadSobrante()
        );
    }

    @Transactional(readOnly = true)
    public int cantidadAsignadaPorNecesidad(String necesidadId) {
        validarId(necesidadId, "ID de necesidad inválido");
        java.time.LocalDate inicio = inicioPeriodo(necesidadId);

        return asignacionRepo.findAll().stream()
            .filter(asignacion -> necesidadId.equals(asignacion.getNecesidadId()))
            .filter(asignacion -> inicio == null || !asignacion.getFecha().toLocalDate().isBefore(inicio))
            .mapToInt(asignacion -> asignacion.getCantidadAsignada() == null
                ? 0
                : asignacion.getCantidadAsignada())
            .sum();
    }

    @Transactional(readOnly = true)
    public StockDTO consultarStock(String productoId) {
        validarId(productoId, "Producto inválido");

        int cantidad = depositoRepo.findAll().stream()
            .mapToInt(deposito -> deposito.stockDisponible(productoId))
            .sum();

        return new StockDTO(productoId, cantidad);
    }

    @Transactional
    public List<AsignacionDTO> asignarDesdeStock(
        String necesidadId,
        String productoId,
        Integer cantidadSolicitada
    ) {
        validarId(necesidadId, "ID de necesidad inválido");
        validarId(productoId, "ID de producto inválido");

        if (cantidadSolicitada == null || cantidadSolicitada <= 0) {
            throw new BusinessRuleException("La cantidad solicitada debe ser positiva");
        }

        List<Deposito> referencias = depositoRepo.findAll().stream()
            .sorted(Comparator.comparing(Deposito::getId))
            .toList();

        List<Deposito> depositos = new ArrayList<>();
        for (Deposito referencia : referencias) {
            depositos.add(
                depositoRepo.findByIdForUpdate(referencia.getId())
                    .orElseThrow(() ->
                        new ResourceNotFoundException(
                            "Depósito no encontrado durante la asignación de stock"
                        ))
            );
        }

        int stockDisponible = depositos.stream()
            .mapToInt(deposito -> deposito.stockDisponible(productoId))
            .sum();

        if (stockDisponible <= 0) {
            return List.of();
        }

        int cantidadRestante = Math.min(cantidadSolicitada, stockDisponible);
        List<AsignacionDTO> asignaciones = new ArrayList<>();

        for (Deposito deposito : depositos) {
            if (cantidadRestante <= 0) {
                break;
            }

            List<Paquete> paquetes = deposito.paquetesEnStockDe(productoId);

            for (Paquete paquete : paquetes) {
                if (cantidadRestante <= 0) {
                    break;
                }

                int cantidadOriginal = paquete.getCantidad();
                int cantidadAsignar = Math.min(cantidadOriginal, cantidadRestante);

                paquete.setCantidad(cantidadAsignar);
                paquete.marcarAsignado();

                Asignacion asignacion = new Asignacion(
                    paquete.getId(),
                    necesidadId,
                    LocalDateTime.now(),
                    EstadoAsignacionEnum.ASIGNADA,
                    cantidadAsignar,
                    OrigenAsignacion.SOLICITUD_ENTIDAD
                );

                Asignacion guardada = asignacionRepo.save(asignacion);

                int sobrante = cantidadOriginal - cantidadAsignar;
                if (sobrante > 0) {
                    deposito.almacenar(
                        new Paquete(
                            paquete.getDonacionId(),
                            paquete.getProducto(),
                            sobrante,
                            EstadoPaquete.EN_STOCK
                        )
                    );
                }

                depositoRepo.save(deposito);
                publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete));
                publicarEvento("asignacion", guardada.getId(), "UPSERT", snapshotAsignacion(deposito, guardada));
                asignaciones.add(mapper.toAsignacionDTO(guardada));
                cantidadRestante -= cantidadAsignar;

                ar.edu.utn.dds.k3003.observability.DomainEvents.info(log,
                    "stock.asignado deposito={} paquete={} asignacion={} necesidad={} cantidad={} sobrante={}",
                    deposito.getId(),
                    paquete.getId(),
                    guardada.getId(),
                    necesidadId,
                    cantidadAsignar,
                    sobrante
                );
            }
        }

        depositos.forEach(deposito -> {
            publicarEvento("deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito));
            publicarPaquetes(deposito);
        });
        return asignaciones;
    }

    private AsignacionDTO registrarResultadoInternamente(
        Deposito deposito,
        Paquete paquete,
        ResultadoMatchmaking resultado
    ) {
        if (!resultado.tieneAsignacion()) {
            paquete.marcarEnStock();
            deposito = depositoRepo.save(deposito);
            publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete));
            publicarEvento("deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito));
            return null;
        }

        paquete.setCantidad(resultado.cantidadAsignada());
        paquete.marcarAsignado();

        var necesidad = resultado.necesidad().orElseThrow();

        Asignacion existente = asignacionRepo.findByPaqueteId(paquete.getId()).orElse(null);
        if (existente != null) {
            return mapper.toAsignacionDTO(existente);
        }

        Asignacion asignacion = new Asignacion(
            paquete.getId(),
            necesidad.id(),
            LocalDateTime.now(),
            EstadoAsignacionEnum.ASIGNADA,
            resultado.cantidadAsignada(),
            OrigenAsignacion.MATCHMAKING
        );

        Asignacion guardada = asignacionRepo.save(asignacion);

        if (resultado.cantidadSobrante() > 0) {
            deposito.almacenar(
                new Paquete(
                    paquete.getDonacionId(),
                    paquete.getProducto(),
                    resultado.cantidadSobrante(),
                    EstadoPaquete.EN_STOCK
                )
            );
        }

        deposito = depositoRepo.save(deposito);
        publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete));
        publicarEvento("asignacion", guardada.getId(), "UPSERT", snapshotAsignacion(deposito, guardada));
        publicarEvento("deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito));
        publicarPaquetes(deposito);
        return mapper.toAsignacionDTO(guardada);
    }

    private Deposito encontrarDepositoPorPaquete(String paqueteId) {
        return depositoRepo.findAll().stream()
            .filter(deposito -> deposito.getStockActual().stream()
                .anyMatch(paquete -> paqueteId.equals(paquete.getId())))
            .findFirst()
            .orElseThrow(() ->
                new ResourceNotFoundException("Depósito del paquete no encontrado"));
    }

    private Paquete buscarPaquete(Deposito deposito, String paqueteId) {
        return deposito.getStockActual().stream()
            .filter(paquete -> paqueteId.equals(paquete.getId()))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Paquete no encontrado"));
    }

    private void validarResultado(ResultadoMatchmakingRequest request) {
        if (request == null) {
            throw new BusinessRuleException("Resultado inválido");
        }

        validarId(request.depositoId(), "ID de depósito inválido");
        validarId(request.paqueteId(), "ID de paquete inválido");

        if (request.cantidadAsignada() == null || request.cantidadAsignada() < 0
            || request.cantidadSobrante() == null || request.cantidadSobrante() < 0) {
            throw new BusinessRuleException("Cantidades del resultado inválidas");
        }
    }

    private void asegurarIntegraciones() {
        if (fachadaDonadores == null || fachadaDonaciones == null) {
            throw new ConflictException("Las fachadas externas no están configuradas");
        }
    }

    private void validarId(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(message);
        }
    }

    @Transactional
    public DepositoDTO modificarDeposito(String id, DepositoDTO dto) {
        if (dto == null || dto.nombre() == null || dto.nombre().isBlank()
            || dto.direccion() == null || dto.direccion().isBlank()
            || dto.capacidadMaxima() == null || dto.capacidadMaxima() <= 0)
            throw new BusinessRuleException("Datos de depósito inválidos");
        Deposito deposito = depositoRepo.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado"));
        if (dto.capacidadMaxima() < deposito.ocupacionActual()) throw new ConflictException("La capacidad no puede ser menor que la ocupación actual");
        deposito.setNombre(dto.nombre()); deposito.setDireccion(dto.direccion()); deposito.setCapacidadMaxima(dto.capacidadMaxima());
        Deposito guardado = depositoRepo.save(deposito);
        publicarEvento("deposito", guardado.getId(), "UPSERT", snapshotDeposito(guardado));
        return mapper.toDepositoDTO(guardado);
    }
    @Transactional(readOnly = true)
    public List<AsignacionDTO> listarAsignaciones() {
        return asignacionRepo.findAll().stream().map(mapper::toAsignacionDTO).toList();
    }

    @Transactional(readOnly = true)
    public double paquetesPendientes() {
        return depositoRepo.findAll().stream().flatMap(d -> d.getStockActual().stream())
            .filter(p -> p.getEstadoPaquete() == EstadoPaquete.PENDIENTE).count();
    }
    @Transactional(readOnly = true)
    public double ocupacionTotal() {
        return depositoRepo.findAll().stream().mapToInt(Deposito::ocupacionActual).sum();
    }
    @Transactional(readOnly = true)
    public double capacidadTotal() {
        return depositoRepo.findAll().stream().mapToInt(Deposito::getCapacidadMaxima).sum();
    }

    public record DepositoSnapshot(String id, double ocupacion, double capacidad) {}
    public record OperacionSnapshot(List<DepositoSnapshot> depositos, double reservadas, LocalDateTime oldest) {}

    @Transactional(readOnly = true)
    public OperacionSnapshot observabilitySnapshot() {
        List<DepositoSnapshot> deposits = depositoRepo.findAll().stream().map(d ->
            new DepositoSnapshot(d.getId(), d.ocupacionActual(), d.getCapacidadMaxima())).toList();
        var pending = asignacionRepo.findAll().stream()
            .filter(a -> a.getEstado() == EstadoAsignacionEnum.ASIGNADA).toList();
        double reserved = pending.stream().mapToInt(Asignacion::getCantidadAsignada).sum();
        LocalDateTime oldest = pending.stream().map(Asignacion::getFecha).filter(Objects::nonNull)
            .min(LocalDateTime::compareTo).orElse(null);
        return new OperacionSnapshot(deposits, reserved, oldest);
    }

    private java.time.LocalDate inicioPeriodo(String necesidadId) {
        return fachadaDonadores instanceof ar.edu.utn.dds.k3003.integration.ConsultaPeriodoNecesidad consulta
            ? consulta.inicioPeriodo(necesidadId) : null;
    }

    @Transactional
    public void reportarEntregaLote(List<String> paqueteIds) {
        if (paqueteIds == null || paqueteIds.isEmpty() || paqueteIds.stream().anyMatch(id -> id == null || id.isBlank())
            || paqueteIds.stream().distinct().count() != paqueteIds.size()) throw new BusinessRuleException("Paquetes del lote inválidos o repetidos");
        asegurarIntegraciones();
        List<Asignacion> asignaciones = paqueteIds.stream().map(id -> asignacionRepo.findByPaqueteId(id)
            .orElseThrow(() -> new ResourceNotFoundException("Asignación no encontrada"))).toList();
        String necesidad = asignaciones.getFirst().getNecesidadId();
        if (asignaciones.stream().anyMatch(a -> !necesidad.equals(a.getNecesidadId()) || a.getEstado() != EstadoAsignacionEnum.ASIGNADA))
            throw new ConflictException("El lote debe contener asignaciones pendientes de una misma necesidad");
        java.time.LocalDate inicio = inicioPeriodo(necesidad);
        if (inicio != null && asignaciones.stream().anyMatch(a -> a.getFecha().toLocalDate().isBefore(inicio)))
            throw new ConflictException("El lote contiene una asignación de un período anterior");
        java.util.Map<String, Deposito> depositos = new java.util.TreeMap<>();
        for (String id : paqueteIds) { Deposito ref = encontrarDepositoPorPaquete(id); depositos.put(ref.getId(), ref); }
        for (String id : new ArrayList<>(depositos.keySet())) depositos.put(id, depositoRepo.findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Depósito no encontrado")));
        List<Asignacion> confirmadas = paqueteIds.stream().map(id -> asignacionRepo.findByPaqueteId(id).orElseThrow()).toList();
        if (confirmadas.stream().anyMatch(a -> a.getEstado() != EstadoAsignacionEnum.ASIGNADA))
            throw new ConflictException("Una asignación del lote ya fue entregada");
        int cantidad = confirmadas.stream().mapToInt(Asignacion::getCantidadAsignada).sum();
        fachadaDonadores.satisfacerNecesidad(necesidad, cantidad);
        java.util.Set<String> donaciones = new java.util.HashSet<>();
        for (Asignacion asignacion : confirmadas) {
            Paquete paquete = depositos.values().stream().flatMap(d -> d.getStockActual().stream())
                .filter(p -> p.getId().equals(asignacion.getPaqueteId())).findFirst().orElseThrow();
            asignacion.cambiarEstado(EstadoAsignacionEnum.COMPLETADA); asignacionRepo.save(asignacion);
            paquete.marcarEntregado(); donaciones.add(paquete.getDonacionId());
        }
        depositos.values().forEach(depositoRepo::save);
        for (Asignacion asignacion : confirmadas) {
            Deposito depositoAsignacion = depositos.values().stream()
                .filter(d -> d.getStockActual().stream().anyMatch(p -> p.getId().equals(asignacion.getPaqueteId())))
                .findFirst().orElseThrow();
            Paquete paquete = buscarPaquete(depositoAsignacion, asignacion.getPaqueteId());
            publicarEvento("paquete", paquete.getId(), "UPSERT", snapshotPaquete(depositoAsignacion.getId(), paquete));
            publicarEvento("asignacion", asignacion.getId(), "UPSERT", snapshotAsignacion(depositoAsignacion, asignacion));
        }
        depositos.values().forEach(deposito -> publicarEvento(
            "deposito", deposito.getId(), "UPSERT", snapshotDeposito(deposito)));
        for (String id : donaciones) aceptarDonacion(id);
        asignaciones.forEach(this::registrarDuracionEntrega);
        ar.edu.utn.dds.k3003.observability.DomainEvents.info(log, "entrega.lote_reportada necesidad={} paquetes={} cantidad={}", necesidad, paqueteIds.size(), cantidad);
    }

    private void aceptarDonacion(String id) {
        var donacion = fachadaDonaciones.buscarDonacionPorID(id);
        if (donacion == null || donacion.estado() != EstadoDonacionEnum.CONQUEJA)
            fachadaDonaciones.cambiarEstadoDeDonacion(id, EstadoDonacionEnum.ACEPTADA);
    }

    private void registrarDuracionEntrega(Asignacion asignacion) {
        if (asignacion.getFecha() == null) return;
        java.time.Duration elapsed = java.time.Duration.between(asignacion.getFecha(), LocalDateTime.now());
        if (elapsed.isNegative()) return;
        io.micrometer.core.instrument.Timer.builder("logistica.entregas.duracion")
            .description("Desde asignación hasta entrega física, por paquete")
            .tag("origen", asignacion.getOrigen() == null ? "DESCONOCIDO" : asignacion.getOrigen().name())
            .minimumExpectedValue(java.time.Duration.ofSeconds(1))
            .maximumExpectedValue(java.time.Duration.ofDays(7))
            .publishPercentileHistogram().register(io.micrometer.core.instrument.Metrics.globalRegistry).record(elapsed);
}

    private void publicarEvento(
        String tipo, String id, String operacion, Map<String, Object> payload
    ) {
        if (readModelEventPublisher != null) {
            readModelEventPublisher.publicar(tipo, id, operacion, payload);
        }
    }

    private void publicarPaquetes(Deposito deposito) {
        deposito.getStockActual().forEach(paquete -> publicarEvento(
            "paquete", paquete.getId(), "UPSERT", snapshotPaquete(deposito.getId(), paquete)));
    }

    private Map<String, Object> snapshotDeposito(Deposito deposito) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", deposito.getId());
        snapshot.put("nombre", deposito.getNombre());
        snapshot.put("direccion", deposito.getDireccion());
        snapshot.put("capacidadMaxima", deposito.getCapacidadMaxima());
        snapshot.put("algoritmo", deposito.getTipoAlgoritmo() == null ? null : deposito.getTipoAlgoritmo().name());
        snapshot.put("ocupacionActual", deposito.ocupacionActual());
        snapshot.put("stockActual", deposito.getStockActual().stream()
            .map(paquete -> snapshotPaquete(deposito.getId(), paquete)).toList());
        return snapshot;
    }

    private Map<String, Object> snapshotPaquete(String depositoId, Paquete paquete) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", paquete.getId());
        snapshot.put("depositoID", depositoId);
        snapshot.put("donacionID", paquete.getDonacionId());
        snapshot.put("productoID", paquete.getProducto());
        snapshot.put("cantidad", paquete.getCantidad());
        snapshot.put("estado", paquete.getEstadoPaquete() == null ? null : paquete.getEstadoPaquete().name());
        return snapshot;
    }

    private Map<String, Object> snapshotAsignacion(Deposito deposito, Asignacion asignacion) {
        Paquete paquete = buscarPaquete(deposito, asignacion.getPaqueteId());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", asignacion.getId());
        snapshot.put("depositoID", deposito.getId());
        snapshot.put("paqueteID", asignacion.getPaqueteId());
        snapshot.put("necesidadID", asignacion.getNecesidadId());
        snapshot.put("donacionID", paquete.getDonacionId());
        snapshot.put("productoID", paquete.getProducto());
        snapshot.put("cantidadAsignada", asignacion.getCantidadAsignada());
        snapshot.put("fecha", asignacion.getFecha());
        snapshot.put("estado", asignacion.getEstado() == null ? null : asignacion.getEstado().name());
        snapshot.put("origen", asignacion.getOrigen() == null ? null : asignacion.getOrigen().name());
        snapshot.put("historial", asignacion.getHistorial().stream()
            .map(cambio -> Map.of("estado", cambio.getEstado().name(), "fecha", cambio.getFecha()))
            .toList());
        return snapshot;
    }

}
